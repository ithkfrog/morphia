package org.mongodb.morphia;

import com.mongodb.DBDecoderFactory;
import com.mongodb.DBRef;
import com.mongodb.MapReduceCommand.OutputType;
import com.mongodb.MongoClient;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MapReduceIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.DeleteOptions;
import com.mongodb.client.model.FindOneAndDeleteOptions;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.FindOptions;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.InsertOneOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.ValidationOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWriter;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.ClassModel;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.mongodb.morphia.aggregation.AggregationPipeline;
import org.mongodb.morphia.aggregation.AggregationPipelineImpl;
import org.mongodb.morphia.annotations.CappedAt;
import org.mongodb.morphia.annotations.Entity;
import org.mongodb.morphia.annotations.PostPersist;
import org.mongodb.morphia.annotations.Validation;
import org.mongodb.morphia.annotations.Version;
import org.mongodb.morphia.logging.Logger;
import org.mongodb.morphia.logging.MorphiaLoggerFactory;
import org.mongodb.morphia.mapping.MappedClass;
import org.mongodb.morphia.mapping.MappedField;
import org.mongodb.morphia.mapping.Mapper;
import org.mongodb.morphia.mapping.MappingException;
import org.mongodb.morphia.mapping.lazy.proxy.ProxyHelper;
import org.mongodb.morphia.query.DefaultQueryFactory;
import org.mongodb.morphia.query.Query;
import org.mongodb.morphia.query.QueryException;
import org.mongodb.morphia.query.QueryFactory;
import org.mongodb.morphia.query.UpdateException;
import org.mongodb.morphia.query.UpdateOperations;
import org.mongodb.morphia.query.UpdateOpsImpl;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.mongodb.BasicDBObject.parse;
import static com.mongodb.DBCollection.ID_FIELD_NAME;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

/**
 * A generic (type-safe) wrapper around mongodb collections
 *
 * @deprecated This is an internal implementation of a published API.  No public alternative planned.
 */
@Deprecated
@SuppressWarnings({"deprecation", "unchecked"})
public class DatastoreImpl implements AdvancedDatastore {
    private static final Logger LOG = MorphiaLoggerFactory.get(DatastoreImpl.class);

    private final Morphia morphia;
    private final MongoClient mongoClient;
    private final MongoDatabase database;
    private final IndexHelper indexHelper;
    private MongoDatabase db;
    private Mapper mapper;
    private WriteConcern defConcern;

    private volatile QueryFactory queryFactory = new DefaultQueryFactory();
    private CodecRegistry codecRegistry;
    private PojoCodecProvider pojoCodecProvider;

    /**
     * Create a new DatastoreImpl
     *
     * @param morphia     the Morphia instance
     * @param mongoClient the connection to the MongoDB instance
     * @param dbName      the name of the database for this data store.
     * @deprecated This is not meant to be directly instantiated by end user code.  Use
     * {@link Morphia#createDatastore(MongoClient, Mapper, String)}
     */
    @Deprecated
    public DatastoreImpl(final Morphia morphia, final MongoClient mongoClient, final String dbName) {
        this(morphia, morphia.getMapper(), mongoClient, dbName);
    }

    /**
     * Create a new DatastoreImpl
     *
     * @param morphia     the Morphia instance
     * @param mapper      an initialised Mapper
     * @param mongoClient the connection to the MongoDB instance
     * @param dbName      the name of the database for this data store.
     * @deprecated This is not meant to be directly instantiated by end user code.  Use
     * {@link Morphia#createDatastore(MongoClient, Mapper, String)}
     */
    @Deprecated
    public DatastoreImpl(final Morphia morphia, final Mapper mapper, final MongoClient mongoClient, final String dbName) {
        this(morphia, mapper, mongoClient, mongoClient.getDatabase(dbName));
    }

    private DatastoreImpl(final Morphia morphia, final Mapper mapper, final MongoClient mongoClient, final MongoDatabase database) {
        this.morphia = morphia;
        this.mapper = mapper;
        this.mongoClient = mongoClient;
        this.database = database;
        this.defConcern = mongoClient.getWriteConcern();
        this.indexHelper = new IndexHelper(mapper, database);

        pojoCodecProvider = morphia.getProviderBuilder().build();
        codecRegistry = fromRegistries(MongoClient.getDefaultCodecRegistry(),
            fromProviders(pojoCodecProvider));

        this.db = database.withCodecRegistry(codecRegistry);
    }

    /**
     * Creates a copy of this Datastore and all its configuration but with a new database
     *
     * @param database the new database to use for operations
     * @return the new Datastore instance
     * @deprecated use {@link Morphia#createDatastore(MongoClient, Mapper, String)}
     */
    @Deprecated
    public DatastoreImpl copy(final String database) {
        return new DatastoreImpl(morphia, mapper, mongoClient, database);
    }

    /**
     * @param source the initial type/collection to aggregate against
     * @return a new query bound to the kind (a specific {@link MongoCollection})
     */
    @Override
    public AggregationPipeline createAggregation(final Class source) {
        return new AggregationPipelineImpl(this, getCollection(source));
    }

    @Override
    public <T> Query<T> createQuery(final Class<T> collection) {
        return newQuery(collection, getCollection(collection));
    }

    @Override
    public <T> UpdateOperations<T> createUpdateOperations(final Class<T> clazz) {
        return new UpdateOpsImpl<>(this, clazz, getMapper());
    }

    @Override
    public <T, V> DeleteResult delete(final Class<T> clazz, final V id) {
        return delete(clazz, id, new DeleteOptions());
    }

    @Override
    public <T, V> DeleteResult delete(final Class<T> clazz, final V id, final DeleteOptions options) {
        return delete(createQuery(clazz).filter(Mapper.ID_KEY, id), options);
    }

    @Override
    public <T, V> DeleteResult delete(final Class<T> clazz, final List<V> ids) {
        return delete(find(clazz).filter(Mapper.ID_KEY + " in", ids));
    }

    @Override
    public <T, V> DeleteResult delete(final Class<T> clazz, final List<V> ids, final DeleteOptions options) {
        return delete(find(clazz).filter(Mapper.ID_KEY + " in", ids), options);
    }

    @Override
    public <T> DeleteResult delete(final Query<T> query) {
        return delete(query, new DeleteOptions());
    }

    public <T> DeleteResult delete(final Query<T> query, final DeleteOptions options) {

        MongoCollection<T> collection = query.getCollection();
        // TODO remove this after testing.
        if (collection == null) {
            collection = getCollection(query.getEntityClass());
        }

        return collection.withWriteConcern(enforceWriteConcern(query.getEntityClass()))
                         .deleteMany(query.getQueryDocument(), options);
    }

    @Override
    public <T> DeleteResult delete(final T entity) {
        return delete(entity, new DeleteOptions());
    }

    /**
     * Deletes the given entity (by @Id), with the WriteConcern
     *
     * @param entity  the entity to delete
     * @param options the options to use when deleting
     * @return results of the delete
     */
    @Override
    public <T> DeleteResult delete(final T entity, final DeleteOptions options) {
        final T wrapped = ProxyHelper.unwrap(entity);
        if (wrapped instanceof Class<?>) {
            throw new MappingException("Did you mean to delete all documents? -- delete(ds.createQuery(???.class))");
        }
        try {
            return delete(wrapped.getClass(), mapper.getId(wrapped), options);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T, V> DeleteResult delete(final String kind, final Class<T> clazz, final V id) {
        return delete(find(kind, clazz).filter(Mapper.ID_KEY, id));
    }

    @Override
    public <T, V> DeleteResult delete(final String kind, final Class<T> clazz, final V id, final DeleteOptions options) {
        return delete(find(kind, clazz).filter(Mapper.ID_KEY, id), options);
    }

    @Override
    public void ensureCaps() {

        for (final ClassModel mc : getClassModels()) {
            final Entity annotation = (Entity) mc.getType().getAnnotation(Entity.class);
            if (annotation != null && annotation.cap().value() > 0) {
                final CappedAt cap = annotation.cap();
                final String collName = mapper.getCollectionName(mc);
                final MongoDatabase database = getDatabase();
                if (database.listCollectionNames().into(new ArrayList<>()).contains(collName)) {
                    final Document dbResult = database.runCommand(new Document("collstats", collName));
                    if (dbResult.containsValue("capped")) {
                        LOG.debug("Collection already exists and is capped already; doing nothing. " + dbResult);
                    } else {
                        LOG.warning(format("Collection already exists with same name(%s) and is not capped; not creating capped version!",
                            collName));
                    }
                } else {
                    final CreateCollectionOptions options = new CreateCollectionOptions();
                    options.capped(true);
                    if (cap.value() > 0) {
                        options.sizeInBytes(cap.value());
                    }
                    if (cap.count() > 0) {
                        options.maxDocuments(cap.count());
                    }
                    getDatabase().createCollection(collName, options);
                    LOG.debug(format("Created capped Collection (%s) with opts %s", collName, options));
                }
            }
        }
    }

    @Override
    public void enableDocumentValidation() {
        for (final MappedClass mc : mapper.getMappedClasses()) {
            process(mc, (Validation) mc.getAnnotation(Validation.class));
        }
    }

    void process(final MappedClass mc, final Validation validation) {
        if (validation != null) {
            String collectionName = mc.getCollectionName();
            Document result = getDatabase().runCommand(new Document("collMod", collectionName)
                                                           .append("validator", parse(validation.value()))
                                                           .append("validationLevel", validation.level().getValue())
                                                           .append("validationAction", validation.action().getValue())
                                                      );

            if (!result.getBoolean("ok")) {
                if (result.getInteger("code") == 26) {
                    ValidationOptions options = new ValidationOptions()
                                                    .validator(parse(validation.value()))
                                                    .validationLevel(validation.level())
                                                    .validationAction(validation.action());
                    getDatabase().createCollection(collectionName, new CreateCollectionOptions().validationOptions(options));
                } else {
                    //                    result.throwOnError();
                    throw new UnsupportedOperationException("need to be updated to extract error: " + result);
                }
            }
        }
    }

    private <T> MongoCollection<T> getMongoCollection(final Class<T> clazz) {
        return getMongoCollection(mapper.getCollectionName(clazz), clazz);
    }

    private <T> MongoCollection<T> getMongoCollection(final String name, final Class<T> clazz) {
        return database.getCollection(name, clazz);
    }

    @Override
    public void ensureIndexes() {
        ensureIndexes(false);
    }

    @Override
    public void ensureIndexes(final boolean background) {
        for (final MappedClass mc : mapper.getMappedClasses()) {
            indexHelper.createIndex(getMongoCollection(mc.getClazz()), mc, background);
        }
    }

    @Override
    public <T> void ensureIndexes(final Class<T> clazz) {
        ensureIndexes(clazz, false);
    }

    @Override
    public <T> void ensureIndexes(final Class<T> clazz, final boolean background) {
        indexHelper.createIndex(getMongoCollection(clazz), mapper.getMappedClass(clazz), background);
    }

    @Override
    public <T> void ensureIndexes(final String collection, final Class<T> clazz) {
        ensureIndexes(collection, clazz, false);
    }

    @Override
    public <T> void ensureIndexes(final String collection, final Class<T> clazz, final boolean background) {
        indexHelper.createIndex(getMongoCollection(collection, clazz), mapper.getMappedClass(clazz), background);
    }

    @Override
    public Key<?> exists(final Object entityOrKey) {
        final Query<?> query = buildExistsQuery(entityOrKey);
        return query.getKey();
    }

    @Override
    public <T> Query<T> find(final Class<T> clazz) {
        return createQuery(clazz);
    }

    /**
     * Find all instances by type in a different collection than what is mapped on the class given skipping some documents and returning a
     * fixed number of the remaining.
     *
     * @param collection The collection use when querying
     * @param clazz      the class to use for mapping the results
     * @param property   the document property to query against
     * @param value      the value to check for
     * @param validate   if true, validate the query
     * @param <T>        the type to query
     * @param <V>        the type to filter value
     * @return the query
     */
    public <T, V> Query<T> find(final String collection, final Class<T> clazz, final String property, final V value,
                                final boolean validate) {
        final Query<T> query = find(collection, clazz);
        if (!validate) {
            query.disableValidation();
        }
        return query.filter(property, value).enableValidation();
    }

    @Override
    public <T> T findAndDelete(final Query<T> query) {
        return findAndDelete(query, new FindOneAndDeleteOptions());
    }

    @Override
    public <T> T findAndDelete(final Query<T> query, final FindOneAndDeleteOptions options) {
        MongoCollection<T> collection = query.getCollection();
        if (collection == null) {
            collection = getCollection(query.getEntityClass());
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing findAndModify(" + collection.getNamespace().getCollectionName() + ") with delete ...");
        }

        return collection.findOneAndDelete(query.getQueryDocument(), options);
    }

    @Override
    public <T> T findAndModify(final Query<T> query, final UpdateOperations<T> operations, final FindOneAndUpdateOptions options) {
        MongoCollection<T> collection = query.getCollection();
        // TODO remove this after testing.
        if (collection == null) {
            collection = getCollection(query.getEntityClass());
        }

        if (LOG.isTraceEnabled()) {
            LOG.info("Executing findAndModify(" + collection.getNamespace().getCollectionName() + ") with update ");
        }

        updateForVersioning(query, operations);

        return collection.findOneAndUpdate(query.getQueryDocument(),
            ((UpdateOpsImpl<T>) operations).getOperations(), options
                                                                 .sort(query.getSortDocument())
                                                                 .projection(query.getFields()));
    }

    @Override
    public <T> T findAndModify(final Query<T> query, final UpdateOperations<T> operations) {
        return findAndModify(query, operations, new FindOneAndUpdateOptions()
                                                    .returnDocument(ReturnDocument.AFTER));
    }

    private <T> void updateForVersioning(final Query<T> query, final UpdateOperations<T> operations) {

        final MappedClass mc = mapper.getMappedClass(query.getEntityClass());
        MappedField field = mc.getMappedVersionField();

        if (field != null) {
            operations.inc(field.getNameToStore());
        }

    }

    @Override
    public <T, V> Query<T> get(final Class<T> clazz, final List<V> ids) {
        return find(clazz).disableValidation().filter(Mapper.ID_KEY + " in", ids).enableValidation();
    }

    @Override
    public <T, V> T get(final Class<T> clazz, final V id) {
        return find(getCollection(clazz).getNamespace().getCollectionName(), clazz, Mapper.ID_KEY, id, true).get();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(final T entity) {
        final T unwrapped = ProxyHelper.unwrap(entity);
        final Object id = mapper.getId(unwrapped);
        if (id == null) {
            throw new MappingException("Could not get id for " + unwrapped.getClass().getName());
        }
        return (T) get(unwrapped.getClass(), id);
    }

    @Override
    public <T> T getByKey(final Class<T> clazz, final Key<T> key) {
        final String collectionName = mapper.getCollectionName(clazz);
        final String keyCollection = key.getCollection();

        Object id = key.getId();
        if (id instanceof Document) {
            ((Document) id).remove(Mapper.CLASS_NAME_FIELDNAME);
        }
        return get(keyCollection != null ? keyCollection : collectionName, clazz, id);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T> List<T> getByKeys(final Class<T> clazz, final List<Key<T>> keys) {

        final Map<String, List<Key>> kindMap = new HashMap<>();
        final List<T> entities = new ArrayList<>();
        // String clazzKind = (clazz==null) ? null :
        // getMapper().getCollectionName(clazz);
        for (final Key<?> key : keys) {
            mapper.coerceCollection(key);

            // if (clazzKind != null && !key.getKind().equals(clazzKind))
            // throw new IllegalArgumentException("Types are not equal (" +
            // clazz + "!=" + key.getKindClass() +
            // ") for key and method parameter clazz");
            //
            if (kindMap.containsKey(key.getCollection())) {
                kindMap.get(key.getCollection()).add(key);
            } else {
                kindMap.put(key.getCollection(), new ArrayList<>(singletonList((Key) key)));
            }
        }
        for (final Map.Entry<String, List<Key>> entry : kindMap.entrySet()) {
            final List<Key> kindKeys = entry.getValue();

            final List<Object> objIds = new ArrayList<>();
            for (final Key key : kindKeys) {
                objIds.add(key.getId());
            }
            final List kindResults = find(entry.getKey(), null).disableValidation().filter("_id in", objIds).asList();
            entities.addAll(kindResults);
        }

        // TODO: order them based on the incoming Keys.
        return entities;
    }

    @Override
    public <T> List<T> getByKeys(final List<Key<T>> keys) {
        return getByKeys(null, keys);
    }

    @Override
    public <T> MongoCollection<T> getCollection(final Class<T> clazz) {
        final String collName = mapper.getCollectionName(clazz);
        return getDatabase().getCollection(collName, clazz);
    }

    @Override
    public <T> long getCount(final T entity) {
        return getCollection(entity.getClass()).count();
    }

    @Override
    public <T> long getCount(final Class<T> clazz) {
        return getCollection(clazz).count();
    }

    @Override
    public <T> long getCount(final Query<T> query) {
        return query.count();
    }

    @Override
    public <T> long getCount(final Query<T> query, final CountOptions options) {
        return query.count(options);
    }

    @Override
    public MongoDatabase getDatabase() {
        return db;
    }

    @Override
    public WriteConcern getDefaultWriteConcern() {
        return defConcern;
    }

    @Override
    public void setDefaultWriteConcern(final WriteConcern wc) {
        defConcern = wc;
    }

    @Override
    @Deprecated
    // use mapper instead.
    public <T> Key<T> getKey(final T entity) {
        return mapper.getKey(entity);
    }

    @Override
    public QueryFactory getQueryFactory() {
        return queryFactory;
    }

    @Override
    public void setQueryFactory(final QueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public <T> MapReduceIterable<T> mapReduce(final MapReduceOptions<T> options) {
        MongoCollection<T> collection = options.getQuery().getCollection();

        final MapReduceIterable<T> iterable = options.apply(collection.mapReduce(options.getMap(), options.getReduce()));

        if (!OutputType.INLINE.equals(options.getOutputType())) {
            iterable.toCollection();
        }

        return iterable;

    }

    @Override
    public <T> void merge(final T entity) {
        merge(entity, getWriteConcern(entity));
    }

    @Override
    public <T> void merge(final T entity, final WriteConcern wc) {
        if (mapper.getKey(entity) == null) {
            throw new MappingException("Could not get ID for " + entity.getClass().getName());
        }
        Document document = toDocument(entity);

        final Object idValue = document.remove(Mapper.ID_KEY);

        final MongoCollection<T> collection = getCollection((Class<T>) entity.getClass())
                                                  .withWriteConcern(wc);

        UpdateResult result = tryVersionedUpdate(collection, entity, new InsertOneOptions());

        if (result == null) {
            final Query<T> query = (Query<T>) createQuery(entity.getClass()).filter(Mapper.ID_KEY, idValue);
            result = update(query, new Document("$set", document), new UpdateOptions()
                                                                       .upsert(false));
        }

        if (result.getModifiedCount() == 0) {
            throw new UpdateException("Nothing updated");
        }

        document.put(Mapper.ID_KEY, idValue);
        postSaveOperations(Collections.singletonList(entity), false);
    }

    @Override
    public <T> Query<T> queryByExample(final T ex) {
        return queryByExample((MongoCollection<T>) getCollection(ex.getClass()), ex);
    }

    @SuppressWarnings("unchecked")
    private <T> Query<T> queryByExample(final MongoCollection<T> coll, final T example) {
        // TODO: think about remove className from baseQuery param below.
        final Class<T> type = (Class<T>) example.getClass();
        final Document query = toDocument(example);
        return newQuery(type, coll, query);
    }

    private <T> Document toDocument(final T entity) {
        final BsonDocument bsonDocument = new BsonDocument();
        final Class<T> aClass = (Class<T>) entity.getClass();
        codecRegistry.get(aClass).encode(new BsonDocumentWriter(bsonDocument), entity,
            EncoderContext.builder()
                          .isEncodingCollectibleDocument(true)
                          .build());

        return new Document(new LinkedHashMap<>(bsonDocument));
    }

    /**
     * Creates and returns a {@link Query} using the underlying {@link QueryFactory}.
     *
     * @see QueryFactory#createQuery(Datastore, MongoCollection, Class, Document)
     */
    private <T> Query<T> newQuery(final Class<T> type, final MongoCollection<T> collection, final Document query) {
        return getQueryFactory().createQuery(this, collection, type, query);
    }

    private <T> UpdateResult tryVersionedUpdate(final MongoCollection<T> origCollection, final T entity,
                                                final InsertOneOptions options) {
        UpdateResult result;
        final MappedClass mc = mapper.getMappedClass(entity);
        if (mc.getFieldsAnnotatedWith(Version.class).isEmpty()) {
            return null;
        }

        // involvedObjects is used not only as a cache but also as a list of what needs to be called for life-cycle methods at the end.
        final Document document = toDocument(entity);

        // try to do an update if there is a @Version field
        final MongoCollection<Document> collection = getDatabase()
                                                         .getCollection(origCollection.getNamespace().getCollectionName())
                                                         .withWriteConcern(WriteConcern.ACKNOWLEDGED);

        final MappedField mfVersion = mc.getMappedVersionField();
        final String versionKeyName = mfVersion.getNameToStore();

        Long oldVersion = (Long) mfVersion.getFieldValue(entity);
        long newVersion = oldVersion == null ? 1 : oldVersion + 1;

        document.put(versionKeyName, newVersion);
        final Object idValue = document.remove(Mapper.ID_KEY);

        if (idValue != null && newVersion != 1) {
            final Query<?> query = newQuery(Document.class, collection)
                                       .disableValidation()
                                       .filter(Mapper.ID_KEY, idValue)
                                       .enableValidation()
                                       .filter(versionKeyName, oldVersion);
            result = update(query, document, new UpdateOptions()
                                                 .bypassDocumentValidation(options.getBypassDocumentValidation()));


            if (result.getModifiedCount() != 1) {
                throw new ConcurrentModificationException(format("Entity of class %s (id='%s',version='%d') was concurrently updated.",
                    entity.getClass().getName(), idValue, oldVersion));
            }
        } else {
            result = saveDocument(collection, document, options);
        }

        return result;
    }

    private UpdateResult saveDocument(final MongoCollection<Document> collection, final Document document,
                                      final InsertOneOptions options) {
        if (document.get(ID_FIELD_NAME) == null) {
            collection.insertOne(document, options);
            return new InsertResult(collection.getWriteConcern().isAcknowledged());
        } else {
            return collection.updateOne(new Document(ID_FIELD_NAME, document.get(ID_FIELD_NAME)), document,
                new UpdateOptions()
                    .bypassDocumentValidation(options.getBypassDocumentValidation())
                    .upsert(true));
        }
    }

    private <T> List<Key<T>> postSaveOperations(final List<T> entities/*, final Map<Object, Document> involvedObjects*/) {
        return postSaveOperations(entities, /*involvedObjects, */true);
    }

    @SuppressWarnings("unchecked")
    private <T> List<Key<T>> postSaveOperations(final List<T> entities, /*final Map<Object, Document> involvedObjects, */
                                                final boolean fetchKeys) {
        List<Key<T>> keys = new ArrayList<>();
        for (final T entity : entities) {

            if (fetchKeys) {
                final Key<T> key = getMapper().getKey(entity);
                if (key == null) {
                    throw new MappingException(format("Missing _id after save on %s", entity.getClass().getName()));
                }
                keys.add(key);
            }
            mapper.getMappedClass(entity).callLifecycleMethods(PostPersist.class, entity, null, mapper);
        }

/*
        for (Entry<Object, Document> entry : involvedObjects.entrySet()) {
            final Object key = entry.getKey();
            mapper.getMappedClass(key).callLifecycleMethods(PostPersist.class, key, entry.getValue(), mapper);

        }
*/
        return keys;
    }

    private <T> WriteConcern enforceWriteConcern(final Class<T> klass) {
        final WriteConcern klassConcern = getWriteConcern(klass);
        return klassConcern != null ? klassConcern : getDefaultWriteConcern();
    }

    /**
     * Gets the write concern for entity or returns the default write concern for this datastore
     *
     * @param clazzOrEntity the class or entity to use when looking up the WriteConcern
     */
    private WriteConcern getWriteConcern(final Object clazzOrEntity) {
        WriteConcern wc = defConcern;
        if (clazzOrEntity != null) {
            final Entity entityAnn = getMapper().getMappedClass(clazzOrEntity).getEntityAnnotation();
            if (entityAnn != null && entityAnn.concern().length() != 0) {
                wc = WriteConcern.valueOf(entityAnn.concern());
            }
        }

        return wc;
    }

    /**
     * @return the Mapper used by this Datastore
     */
    public Mapper getMapper() {
        return mapper;
    }

    /**
     * Sets the Mapper this Datastore uses
     *
     * @param mapper the new Mapper
     */
    public void setMapper(final Mapper mapper) {
        this.mapper = mapper;
    }

    private Query<?> buildExistsQuery(final Object entityOrKey) {
        final Object unwrapped = ProxyHelper.unwrap(entityOrKey);
        final Key<?> key = mapper.getKey(unwrapped);
        final Object id = key.getId();
        if (id == null) {
            throw new MappingException("Could not get id for " + unwrapped.getClass().getName());
        }

        return find(key.getCollection(), key.getType()).filter(Mapper.ID_KEY, key.getId());
    }

    /**
     * Creates and returns a {@link Query} using the underlying {@link QueryFactory}.
     *
     * @see QueryFactory#createQuery(Datastore, MongoCollection, Class)
     */
    private <T> Query<T> newQuery(final Class<T> type, final MongoCollection<T> collection) {
        return getQueryFactory().createQuery(this, collection, type);
    }

    @SuppressWarnings("unchecked")
    private Collection<ClassModel<?>> getClassModels() {
        try {
            final Field field = pojoCodecProvider.getClass().getDeclaredField("classModels");
            final Map<Class<?>, ClassModel<?>> map = (Map<Class<?>, ClassModel<?>>) field.get(pojoCodecProvider);
            return map.values();
        } catch (Exception e) {
            return emptyList();
        }
    }

    @Override
    public AggregationPipeline createAggregation(final String collection, final Class<?> clazz) {
        final MongoCollection<?> coll = getDatabase().getCollection(collection, clazz);
        return new AggregationPipelineImpl(this, coll);
    }

    @Override
    public <T> Query<T> createQuery(final String collection, final Class<T> type) {
        return newQuery(type, getDatabase().getCollection(collection, type));
    }

    @Override
    public <T> Query<T> createQuery(final Class<T> clazz, final Document q) {
        return newQuery(clazz, getCollection(clazz), q);
    }

    @Override
    public <T> Query<T> createQuery(final String collection, final Class<T> type, final Document q) {
        return newQuery(type, getCollection(collection, type), q);
    }

    @Override
    public <T> UpdateOperations<T> createUpdateOperations(final Class<T> type, final Document ops) {
        final UpdateOpsImpl<T> upOps = (UpdateOpsImpl<T>) createUpdateOperations(type);
        upOps.setOperations(ops);
        return upOps;
    }

    @Override
    public Key<?> exists(final Object entityOrKey, final ReadPreference readPreference) {
        Query<?> query = buildExistsQuery(entityOrKey);
        if (readPreference != null) {
            query = query.cloneQuery().setReadPreference(readPreference);
        }
        return query.getKey(new FindOptions());
    }

    @Override
    public <T> Query<T> find(final String collection, final Class<T> clazz) {
        return createQuery(collection, clazz);
    }

    @Override
    public <T, V> Query<T> find(final String collection, final Class<T> clazz, final String property, final V value, final int offset,
                                final int size) {
        return find(collection, clazz, property, value, true);
    }

    @Override
    public <T> T get(final Class<T> clazz, final DBRef ref) {
        return getDatabase().getCollection(ref.getCollectionName(), clazz).find(new Document("_id", ref.getId())).first();
    }

    @Override
    public <T, V> T get(final String collection, final Class<T> clazz, final V id) {
        final List<T> results = find(collection, clazz, Mapper.ID_KEY, id, 0, 1).asList();
        if (results == null || results.isEmpty()) {
            return null;
        }
        return results.get(0);
    }

    @Override
    public long getCount(final String collection) {
        return getCollection(collection, null).count();
    }

    @Override
    public <T> Key<T> insert(final T entity) {
        return insert(entity, new InsertOneOptions());
    }

    @Override
    public <T> Key<T> insert(final T entity, final InsertOneOptions options) {
        final Class<T> aClass = (Class<T>) entity.getClass();
        final MongoCollection<T> collection = getCollection(aClass);
        return insert(collection, entity, options, enforceWriteConcern(aClass));
    }

    @Override
    public <T> Key<T> insert(final String collection, final T entity) {
        return insert(getCollection(collection, (Class<T>) entity.getClass()), entity,
            new InsertOneOptions(), enforceWriteConcern(entity.getClass()));
    }

    @Override
    public <T> Key<T> insert(final String collection, final T entity, final InsertOneOptions options) {
        return insert(this.getCollection(collection, (Class<T>) entity.getClass()), entity, options,
            enforceWriteConcern(entity.getClass()));
    }

    /**
     * Inserts entities in to the database
     *
     * @param entities the entities to insert
     * @param <T>      the type of the entities
     * @return the keys of entities
     */
    @Override
    public <T> List<Key<T>> insert(final List<T> entities) {
        return insert(entities, new InsertManyOptions());
    }

    @Override
    public <T> List<Key<T>> insert(final List<T> entities, final InsertManyOptions options) {
        if (entities.isEmpty()) {
            return emptyList();
        }
        final Class<T> first = (Class<T>) entities.get(0).getClass();
        return insert(getCollection(first), entities, options, enforceWriteConcern(first));
    }

    @Override
    public <T> List<Key<T>> insert(final String collection, final List<T> entities) {
        return insert(collection, entities, new InsertManyOptions());
    }

    @Override
    public <T> List<Key<T>> insert(final String collection, final List<T> entities, final InsertManyOptions options) {
        if (entities.isEmpty()) {
            return emptyList();
        }
        final Class<?> entityClass = entities.get(0).getClass();
        return insert(getDatabase().getCollection(collection, (Class<T>) entityClass), entities, options,
            enforceWriteConcern(entityClass));
    }
    private <T> List<Key<T>> insert(final MongoCollection<T> collection, final List<T> entities, final InsertManyOptions options,
                                    WriteConcern wc) {
        if (entities.isEmpty()) {
            return emptyList();
        }

        collection
            .withWriteConcern(wc)
            .insertMany(entities, options);

        return postSaveOperations(entities);
    }

    protected <T> Key<T> insert(final MongoCollection<T> collection, final T entity, final InsertOneOptions options, WriteConcern wc) {
        collection
            .withWriteConcern(wc)
            .insertOne(entity, options);

        return postSaveOperations(singletonList(entity)).get(0);
    }

    /**
     * Inserts an entity in to the database
     *
     * @param collection the collection to query against
     * @param entity     the entity to insert
     * @param wc         the WriteConcern to use when deleting
     * @param <T>        the type of the entities
     * @return the key of entity
     */
    public <T> Key<T> insert(final String collection, final T entity, final WriteConcern wc) {
        return insert(getCollection(collection, (Class<T>) entity.getClass()), entity,
            new InsertOneOptions(), wc);
    }

    @Override
    public <T> Query<T> queryByExample(final String collection, final T ex) {
        return queryByExample(getDatabase().getCollection(collection, (Class<T>) ex.getClass()), ex);
    }

    @Override
    public <T> Key<T> save(final String collection, final T entity) {
        return save(collection, entity, new InsertOneOptions());
    }

    @Override
    public <T> Key<T> save(final String collection, final T entity, final InsertOneOptions options) {
        return save(getCollection(collection, (Class<T>) entity.getClass()), entity, options, enforceWriteConcern(entity.getClass()));
    }

    @Override
    public <T> List<Key<T>> save(final List<T> entities) {
        return save(entities, new InsertManyOptions());
    }

    @Override
    public <T> List<Key<T>> save(final List<T> entities, final InsertManyOptions options) {
        if (entities.isEmpty()) {
            return emptyList();
        }
        final Class<T> first = (Class<T>) entities.get(0).getClass();

        getCollection(first)
            .withWriteConcern(enforceWriteConcern(first))
            .insertMany(entities, options);

        return entities.stream().map(mapper::getKey)
                       .collect(Collectors.toList());
    }

    @Override
    public <T> Key<T> save(final T entity) {
        return save(entity, new InsertOneOptions(), enforceWriteConcern(entity.getClass()));
    }

    @Override
    public <T> Key<T> save(final T entity, final WriteConcern writeConcern) {
        return save(entity, new InsertOneOptions(), writeConcern);
    }

    @Override
    public <T> Key<T> save(final T entity, final InsertOneOptions options) {
        if (entity == null) {
            throw new UpdateException("Can not persist a null entity");
        }

        final Class<?> aClass = entity.getClass();
        final MongoCollection<T> collection = (MongoCollection<T>) getCollection(aClass)
                                                                       .withWriteConcern(enforceWriteConcern(aClass));
        return save(collection, entity, options, enforceWriteConcern(aClass));
    }

    @Override
    public <T> Key<T> save(final T entity, final InsertOneOptions options, final WriteConcern writeConcern) {
        if (entity == null) {
            throw new UpdateException("Can not persist a null entity");
        }

        final MongoCollection<T> collection = (MongoCollection<T>) getCollection(entity.getClass());
        return save(collection, entity, options, writeConcern);
    }

    private <T> Key<T> save(final MongoCollection<T> collection, final T entity, final InsertOneOptions options,
                            WriteConcern writeConcern) {
        if (entity == null) {
            throw new UpdateException("Can not persist a null entity");
        }

        if (tryVersionedUpdate(collection, entity, options) == null) {
            collection
                .withWriteConcern(writeConcern)
                .insertOne(entity, options);
        }

        return postSaveOperations(singletonList(entity)).get(0);
    }

    protected <T> MongoCollection<T> getCollection(final String kind, final Class<T> aClass) {
        if (kind == null) {
            return null;
        }
        return getDatabase().getCollection(kind, aClass);
    }

    @Deprecated
    protected Object getId(final Object entity) {
        return mapper.getId(entity);
    }

    @SuppressWarnings("unchecked")
    private <T> UpdateResult update(final Query<T> query, final Document update) {
        return update(query, update, new UpdateOptions()
                                         .upsert(false));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> UpdateResult update(final T entity, final UpdateOperations<T> operations) {
        if (entity instanceof Query) {
            return update((Query<T>) entity, operations);
        }

        final MappedClass mc = mapper.getMappedClass(entity);
        Query<?> query = createQuery(mapper.getMappedClass(entity).getClazz())
                             .disableValidation()
                             .filter(Mapper.ID_KEY, mapper.getId(entity));
        if (!mc.getFieldsAnnotatedWith(Version.class).isEmpty()) {
            final MappedField field = mc.getFieldsAnnotatedWith(Version.class).get(0);
            query.field(field.getNameToStore()).equal(field.getFieldValue(entity));
        }

        return update((Query<T>) query, operations);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> UpdateResult update(final Key<T> key, final UpdateOperations<T> operations) {
        Class<T> clazz = (Class<T>) key.getType();
        if (clazz == null) {
            clazz = (Class<T>) mapper.getClassFromCollection(key.getCollection());
        }
        return update(createQuery(clazz).disableValidation().filter(Mapper.ID_KEY, key.getId()), operations, new UpdateOptions());
    }

    @Override
    public <T> UpdateResult update(final Query<T> query, final UpdateOperations<T> operations) {
        return update(query, operations, new UpdateOptions()
                                             .upsert(false)
            //                                             .multi(true)
                     );
    }

    @Override
    public <T> UpdateResult update(final Query<T> query, final UpdateOperations<T> operations, final UpdateOptions options) {
        MongoCollection<T> collection = query.getCollection();
        // TODO remove this after testing.
        if (collection == null) {
            collection = getCollection(query.getEntityClass());
        }

        final MappedClass mc = getMapper().getMappedClass(query.getEntityClass());
        final List<MappedField> fields = mc.getFieldsAnnotatedWith(Version.class);

        Document queryObject = query.getQueryDocument();
        if (operations.isIsolated()) {
            queryObject.put("$isolated", true);
        }

        if (!fields.isEmpty()) {
            operations.inc(fields.get(0).getNameToStore(), 1);
        }

        final Document update = ((UpdateOpsImpl) operations).getOperations();
        if (LOG.isTraceEnabled()) {
            LOG.trace(format("Executing update(%s) for query: %s, ops: %s,upsert: %s",
                collection.getNamespace().getCollectionName(), queryObject, update, /*options.isMulti(), */options.isUpsert()));
        }

        return collection.updateMany(queryObject, update, options);
    }

    @SuppressWarnings("unchecked")
    private <T> UpdateResult update(final Query<T> query,
                                    final Document update,
                                    final UpdateOptions options) {

        MongoCollection<T> collection = query.getCollection();
        // TODO remove this after testing.
        if (collection == null) {
            collection = getCollection(query.getEntityClass());
        }

        if (query.getSortDocument() != null && !query.getSortDocument().keySet().isEmpty()) {
            throw new QueryException("sorting is not allowed for updates.");
        }

        Document queryObject = query.getQueryDocument();

        final MappedClass mc = getMapper().getMappedClass(query.getEntityClass());
        final List<MappedField> fields = mc.getFieldsAnnotatedWith(Version.class);
        if (!fields.isEmpty()) {
            final MappedField versionMF = fields.get(0);
            if (update.get(versionMF.getNameToStore()) == null) {
                if (!update.containsKey("$inc")) {
                    update.put("$inc", new Document(versionMF.getNameToStore(), 1));
                } else {
                    ((Map<String, Object>) (update.get("$inc"))).put(versionMF.getNameToStore(), 1);
                }
            }
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace(format("Executing update(%s) for query: %s, ops: %s, upsert: %s",
                collection.getNamespace().getCollectionName(), queryObject, update, options.isUpsert()));
        }

        return collection.updateOne(queryObject, update);
    }

    private static class InsertResult extends UpdateResult {
        private boolean acknowledged;

        public InsertResult(final boolean acknowledged) {
            this.acknowledged = acknowledged;
        }

        @Override
        public boolean wasAcknowledged() {
            return acknowledged;
        }

        @Override
        public long getMatchedCount() {
            return 0;
        }

        @Override
        public boolean isModifiedCountAvailable() {
            return true;
        }

        @Override
        public long getModifiedCount() {
            return 1;
        }

        @Override
        public BsonValue getUpsertedId() {
            return null;
        }
    }
}