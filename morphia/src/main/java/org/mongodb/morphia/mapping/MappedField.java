/*
 * Copyright 2008-2016 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.morphia.mapping;


import com.mongodb.DBObject;
import com.mongodb.DBRef;
import org.mongodb.morphia.Key;
import org.mongodb.morphia.annotations.AlsoLoad;
import org.mongodb.morphia.annotations.ConstructorArgs;
import org.mongodb.morphia.annotations.Embedded;
import org.mongodb.morphia.annotations.Id;
import org.mongodb.morphia.annotations.Indexed;
import org.mongodb.morphia.annotations.NotSaved;
import org.mongodb.morphia.annotations.Property;
import org.mongodb.morphia.annotations.Reference;
import org.mongodb.morphia.annotations.Serialized;
import org.mongodb.morphia.annotations.Text;
import org.mongodb.morphia.annotations.Transient;
import org.mongodb.morphia.annotations.Version;
import org.mongodb.morphia.utils.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Arrays.asList;


/**
 * Represents the mapping of this field to/from mongodb (name, list<annotation>)
 *
 * @author Scott Hernandez
 */
@SuppressWarnings("unchecked")
public class MappedField {
    // The Annotations to look for when reflecting on the field (stored in the mappingAnnotations)
    private static final List<Class<? extends Annotation>> INTERESTING = new ArrayList<Class<? extends Annotation>>();

    static {
        INTERESTING.add(Serialized.class);
        INTERESTING.add(Indexed.class);
        INTERESTING.add(Property.class);
        INTERESTING.add(Reference.class);
        INTERESTING.add(Embedded.class);
        INTERESTING.add(Id.class);
        INTERESTING.add(Version.class);
        INTERESTING.add(ConstructorArgs.class);
        INTERESTING.add(AlsoLoad.class);
        INTERESTING.add(NotSaved.class);
        INTERESTING.add(Text.class);
    }

    // Annotations that have been found relevant to mapping
    private final Map<Class<? extends Annotation>, Annotation> foundAnnotations = new HashMap<Class<? extends Annotation>, Annotation>();
    private final List<MappedField> typeParameters = new ArrayList<MappedField>();
    private Field field; // the field :)
    private Class realType; // the real type
    private Constructor constructor; // the constructor for the type
    private Type subType; // the type (T) for the Collection<T>/T[]/Map<?,T>
    private Type mapKeyType; // the type (T) for the Map<T,?>
    private boolean isSingleValue = true; // indicates the field is a single value
    private boolean isMongoType;
    // indicated the type is a mongo compatible type (our version of value-type)
    private boolean isMap; // indicated if it implements Map interface
    private boolean isSet; // indicated if the collection is a set
    //for debugging
    private boolean isArray; // indicated if it is an Array
    private boolean isCollection; // indicated if the collection is a list)
    private Type genericType;

    MappedField(final Field f) {
        f.setAccessible(true);
        field = f;
        realType = field.getType();
        genericType = field.getGenericType();
        discover();
    }

    /**
     * Creates a MappedField
     *
     * @param field   the Type for the field
     * @param type   the Type for the field
     * @param mapper the Mapper to use
     */
    MappedField(final Field field, final Type type, final Mapper mapper) {
        this.field = field;
        genericType = type;
    }

    /**
     * Adds the annotation, if it exists on the field.
     *
     * @param clazz the annotation to add
     */
    public void addAnnotation(final Class<? extends Annotation> clazz) {
        if (field.isAnnotationPresent(clazz)) {
            foundAnnotations.put(clazz, field.getAnnotation(clazz));
        }
    }

    /**
     * @param clazz the annotation to search for
     * @param <T>   the type of the annotation
     * @return the annotation instance if it exists on this field
     */
    @SuppressWarnings("unchecked")
    public <T extends Annotation> T getAnnotation(final Class<T> clazz) {
        return (T) foundAnnotations.get(clazz);
    }

    /**
     * @return the annotations found while mapping
     */
    public Map<Class<? extends Annotation>, Annotation> getAnnotations() {
        return foundAnnotations;
    }

    /**
     * @return a constructor for the type represented by the field
     */
    public Constructor getCTor() {
        return constructor;
    }

    /**
     * @return the concrete type of the MappedField
     */
    public Class getConcreteType() {
        final Embedded e = getAnnotation(Embedded.class);
        if (e != null) {
            final Class concrete = e.concreteClass();
            if (concrete != Object.class) {
                return concrete;
            }
        }

        final Property p = getAnnotation(Property.class);
        if (p != null) {
            final Class concrete = p.concreteClass();
            if (concrete != Object.class) {
                return concrete;
            }
        }
        return getType();
    }

    /**
     * @param dbObj the DBObject get the value from
     * @return the value from best mapping of this field
     */
    public Object getDbObjectValue(final DBObject dbObj) {
        return dbObj.get(getFirstFieldName(dbObj));
    }

    /**
     * @return the declaring class of the java field
     */
    public Class getDeclaringClass() {
        return field.getDeclaringClass();
    }

    /**
     * @return the underlying java field
     */
    public Field getField() {
        return field;
    }

    /**
     * Gets the value of the field mapped on the instance given.
     *
     * @param instance the instance to use
     * @return the value stored in the java field
     */
    public Object getFieldValue(final Object instance) {
        try {
            return field.get(instance);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the field name to use when converting from a DBObject
     *
     * @param dbObj the DBObject to scan for alternate names
     * @return the value of this field mapped from the DBObject
     * @see AlsoLoad
     */
    private String getFirstFieldName(final DBObject dbObj) {
        String fieldName = getNameToStore();
        boolean foundField = false;
        for (final String n : getLoadNames()) {
            if (dbObj.containsField(n)) {
                if (!foundField) {
                    foundField = true;
                    fieldName = n;
                } else {
                    throw new MappingException(format("Found more than one field from @AlsoLoad %s", getLoadNames()));
                }
            }
        }
        return fieldName;
    }

    /**
     * @return the name of the java field, as declared on the class
     */
    public String getJavaFieldName() {
        return field.getName();
    }

    /**
     * @return the name of the field's (key)name for mongodb, in order of loading.
     */
    public List<String> getLoadNames() {
        final List<String> names = new ArrayList<String>();
        names.add(getMappedFieldName());

        final AlsoLoad al = (AlsoLoad) foundAnnotations.get(AlsoLoad.class);
        if (al != null && al.value().length > 0) {
            names.addAll(asList(al.value()));
        }

        return names;
    }

    /**
     * If the underlying java type is a map then it returns T from Map<T,V>
     *
     * @return the type of the map key
     */
    public Class getMapKeyClass() {
        return toClass(mapKeyType);
    }

    /**
     * @return the name of the field's (key)name for mongodb
     */
    public String getNameToStore() {
        return getMappedFieldName();
    }

    /**
     * If the java field is a list/array/map then the sub-type T is returned (ex. List<T>, T[], Map<?,T>
     *
     * @return the parameterized type of the field
     */
    public Class getSubClass() {
        return toClass(subType);
    }

    /**
     * If the java field is a list/array/map then the sub-type T is returned (ex. List<T>, T[], Map<?,T>
     *
     * @return the parameterized type of the field
     */
    public Type getSubType() {
        return subType;
    }

    /**
     * @return true if this field is marked as transient
     */
    public boolean isTransient() {
        return hasAnnotation(Transient.class) || Modifier.isTransient(field.getModifiers());
    }

    void setSubType(final Type subType) {
        this.subType = subType;
    }

    /**
     * @return the type of the underlying java field
     */
    public Class getType() {
        return realType;
    }

    /**
     * @return the type parameters defined on the field
     */
    public List<MappedField> getTypeParameters() {
        return typeParameters;
    }

    /**
     * Indicates whether the annotation is present in the mapping (does not check the java field annotations, just the ones discovered)
     *
     * @param ann the annotation to search for
     * @return true if the annotation was found
     */
    public boolean hasAnnotation(final Class ann) {
        return foundAnnotations.containsKey(ann);
    }

    /**
     * @return true if the MappedField is an array
     */
    public boolean isArray() {
        return isArray;
    }

    /**
     * @return true if the MappedField is a Map
     */
    public boolean isMap() {
        return isMap;
    }

    /**
     * @return true if this field is a container type such as a List, Map, Set, or array
     */
    public boolean isMultipleValues() {
        return !isSingleValue();
    }

    /**
     * @return true if this field is a reference to a foreign document
     * @see Reference
     * @see Key
     * @see DBRef
     */
    public boolean isReference() {
        return hasAnnotation(Reference.class) || Key.class == getConcreteType() || DBRef.class == getConcreteType();
    }

    /**
     * @return true if the MappedField is a Set
     */
    public boolean isSet() {
        return isSet;
    }

    /**
     * @return true if this field is not a container type such as a List, Map, Set, or array
     */
    public boolean isSingleValue() {
        if (!isSingleValue && !isMap && !isSet && !isArray && !isCollection) {
            throw new RuntimeException("Not single, but none of the types that are not-single.");
        }
        return isSingleValue;
    }

    /**
     * @return true if type is understood by MongoDB and the driver
     */
    public boolean isTypeMongoCompatible() {
        return isMongoType;
    }

    /**
     * Adds the annotation even if not on the declared class/field.
     *
     * @param ann the annotation to add
     * @return ann the annotation
     */
    public Annotation putAnnotation(final Annotation ann) {
        return foundAnnotations.put(ann.getClass(), ann);
    }

    /**
     * Sets the value for the java field
     *
     * @param instance the instance to update
     * @param value    the value to set
     */
    public void setFieldValue(final Object instance, final Object value) {
        try {
            field.set(instance, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(getMappedFieldName()).append(" (");
        sb.append(" type:").append(realType.getSimpleName()).append(",");

        if (isSingleValue()) {
            sb.append(" single:true,");
        } else {
            sb.append(" multiple:true,");
            sb.append(" subtype:").append(getSubClass()).append(",");
        }
        if (isMap()) {
            sb.append(" map:true,");
            if (getMapKeyClass() != null) {
                sb.append(" map-key:").append(getMapKeyClass().getSimpleName());
            } else {
                sb.append(" map-key: class unknown! ");
            }
        }

        if (isSet()) {
            sb.append(" set:true,");
        }
        if (isCollection) {
            sb.append(" collection:true,");
        }
        if (isArray) {
            sb.append(" array:true,");
        }

        //remove last comma
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }

        sb.append("); ").append(foundAnnotations.toString());
        return sb.toString();
    }

    /**
     * Discovers interesting (that we care about) things about the field.
     */
    protected void discover() {
        for (final Class<? extends Annotation> clazz : INTERESTING) {
            addAnnotation(clazz);
        }

        // check the main type
        isMongoType = ReflectionUtils.isPropertyType(realType);

        // if the main type isn't supported by the Mongo, see if the subtype is.
        // works for T[], List<T>, Map<?, T>, where T is Long/String/etc.
        if (!isMongoType && subType != null) {
            isMongoType = ReflectionUtils.isPropertyType(subType);
        }

        if (!isMongoType && !isSingleValue && (subType == null || subType == Object.class)) {
            isMongoType = true;
        }
    }

    /**
     * @return the name of the field's key-name for mongodb
     */
    protected String getMappedFieldName() {
        if (hasAnnotation(Id.class)) {
            return Mapper.ID_KEY;
        } else if (hasAnnotation(Property.class)) {
            final Property mv = (Property) foundAnnotations.get(Property.class);
            if (!mv.value().equals(Mapper.IGNORED_FIELDNAME)) {
                return mv.value();
            }
        } else if (hasAnnotation(Reference.class)) {
            final Reference mr = (Reference) foundAnnotations.get(Reference.class);
            if (!mr.value().equals(Mapper.IGNORED_FIELDNAME)) {
                return mr.value();
            }
        } else if (hasAnnotation(Embedded.class)) {
            final Embedded me = (Embedded) foundAnnotations.get(Embedded.class);
            if (!me.value().equals(Mapper.IGNORED_FIELDNAME)) {
                return me.value();
            }
        } else if (hasAnnotation(Serialized.class)) {
            final Serialized me = (Serialized) foundAnnotations.get(Serialized.class);
            if (!me.value().equals(Mapper.IGNORED_FIELDNAME)) {
                return me.value();
            }
        } else if (hasAnnotation(Version.class)) {
            final Version me = (Version) foundAnnotations.get(Version.class);
            if (!me.value().equals(Mapper.IGNORED_FIELDNAME)) {
                return me.value();
            }
        }

        return field.getName();
    }

    private void discoverMultivalued() {
        if (realType.isArray()
            || Collection.class.isAssignableFrom(realType)
            || Map.class.isAssignableFrom(realType)
            || GenericArrayType.class.isAssignableFrom(genericType.getClass())) {

            isSingleValue = false;

            isMap = Map.class.isAssignableFrom(realType);
            isSet = Set.class.isAssignableFrom(realType);
            //for debugging
            isCollection = Collection.class.isAssignableFrom(realType);
            isArray = realType.isArray();

            //for debugging with issue
            if (!isMap && !isSet && !isCollection && !isArray) {
                throw new MappingException(format("%s.%s is not a map/set/collection/array : %s", field.getName(),
                                                  field.getDeclaringClass(), realType));
            }

            // get the subtype T, T[]/List<T>/Map<?,T>; subtype of Long[], List<Long> is Long
            subType = (realType.isArray()) ? realType.getComponentType() : ReflectionUtils.getParameterizedType(field, isMap ? 1 : 0);

            if (isMap) {
                mapKeyType = ReflectionUtils.getParameterizedType(field, 0);
            }
        }
    }

    void setIsMap(final boolean isMap) {
        this.isMap = isMap;
    }

    void setIsMongoType(final boolean isMongoType) {
        this.isMongoType = isMongoType;
    }

    void setIsSet(final boolean isSet) {
        this.isSet = isSet;
    }

    void setMapKeyType(final Class mapKeyType) {
        this.mapKeyType = mapKeyType;
    }
}
