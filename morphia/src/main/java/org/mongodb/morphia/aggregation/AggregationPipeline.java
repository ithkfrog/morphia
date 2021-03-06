package org.mongodb.morphia.aggregation;


import com.mongodb.AggregationOptions;
import com.mongodb.ReadPreference;
import com.mongodb.client.model.UnwindOptions;
import org.mongodb.morphia.query.Query;
import org.mongodb.morphia.query.Sort;

import java.util.Iterator;
import java.util.List;

/**
 * This defines the pipeline used in aggregation operations
 *
 * @mongodb.driver.manual core/aggregation-pipeline/ Aggregation Pipeline
 */
public interface AggregationPipeline {
    /**
     * Executes the pipeline and aggregates the output in to the type mapped by the target type using the default options as defined in
     * {@link AggregationOptions}.
     *
     * @param target The class to use when iterating over the results
     * @param <U>    type of the results
     * @return an iterator of the computed results
     */
    <U> Iterator<U> aggregate(Class<U> target);

    /**
     * Executes the pipeline and aggregates the output in to the type mapped by the target type.
     *
     * @param target  The class to use when iterating over the results
     * @param options The options to apply to this aggregation
     * @param <U>     type of the results
     * @return an iterator of the computed results
     */
    <U> Iterator<U> aggregate(Class<U> target, AggregationOptions options);

    /**
     * Executes the pipeline and aggregates the output in to the type mapped by the target type.
     *
     * @param target         The class to use when iterating over the results
     * @param options        The options to apply to this aggregation
     * @param readPreference The read preference to apply to this pipeline
     * @param <U>            type of the results
     * @return an iterator of the computed results
     */
    <U> Iterator<U> aggregate(Class<U> target, AggregationOptions options, ReadPreference readPreference);

    /**
     * Executes the pipeline and aggregates the output in to the type mapped by the target type.
     *
     * @param collectionName The collection in which to store the results of the aggregation overriding the mapped value in target
     * @param target         The class to use when iterating over the results
     * @param options        The options to apply to this aggregation
     * @param readPreference The read preference to apply to this pipeline
     * @param <U>            type of the results
     * @return an iterator of the computed results
     */
    <U> Iterator<U> aggregate(String collectionName, Class<U> target, AggregationOptions options, ReadPreference readPreference);

    /**
     * Returns an ordered stream of documents based on the proximity to a geospatial point. Incorporates the functionality of $match,
     * $sort,
     * and $limit for geospatial data. The output documents include an additional distance field and can include a location identifier
     * field.
     *
     * @param geoNear the geospatial parameters to apply to the pipeline
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/geoNear $geoNear
     */
    AggregationPipeline geoNear(GeoNear geoNear);

    /**
     * Groups input documents by a specified identifier expression and applies the accumulator expression(s), if specified, to each group .
     * Consumes all input documents and outputs one document per each distinct group. The output documents only contain the identifier
     * field and, if specified, accumulated fields.  The ID for this group is null.
     *
     * @param groupings the group definitions
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/group $group
     */
    AggregationPipeline group(Group... groupings);

    /**
     * Groups input documents by a specified identifier expression and applies the accumulator expression(s), if specified, to each group .
     * Consumes all input documents and outputs one document per each distinct group. The output documents only contain the identifier
     * field
     * and, if specified, accumulated fields.
     *
     * @param id        the ID of the group create
     * @param groupings the group definitions
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/group $group
     */
    AggregationPipeline group(String id, Group... groupings);

    /**
     * @param id        the ID of the group create
     * @param groupings the group definitions
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/group $group
     * @see #group(String, Group...)
     */
    AggregationPipeline group(List<Group> id, Group... groupings);

    /**
     * Passes the first n documents unmodified to the pipeline where n is the specified limit. For each input document, outputs either one
     * document (for the first n documents) or zero documents (after the first n documents).
     *
     * @param count the maximum number of documents to return
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/limit $limit
     */
    AggregationPipeline limit(int count);

    /**
     * Performs a left outer join to an unsharded collection in the same database to filter in documents from the "joined" collection for
     * processing. The $lookup stage does an equality match between a field from the input documents with a field from the documents of the
     * “joined” collection.  To each input document, the $lookup stage adds a new array field whose elements are the matching documents
     * from the “joined” collection. The $lookup stage passes these reshaped documents to the next stage.
     *
     * @param from         the collection to join
     * @param localField   the field from the input documents
     * @param foreignField the field from the documents of the "from" collection
     * @param as           the output array field
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/lookup $lookup
     * @since 1.2
     */
    AggregationPipeline lookup(String from, String localField, String foreignField, String as);

    /**
     * Filters the document stream to allow only matching documents to pass unmodified into the next pipeline stage. $match uses standard
     * MongoDB queries. For each input document, outputs either one document (a match) or zero documents (no match).
     *
     * @param query the query to use when matching
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/match $match
     */
    AggregationPipeline match(Query query);

    /**
     * Randomly selects the specified number of documents from the previous pipeline stage.
     * @param sampleSize the number of documents to select
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/match $sample
     */
    AggregationPipeline sample(int sampleSize);

    /**
     * Places the output of the aggregation in the collection mapped by the target type using the default options as defined in {@link
     * AggregationOptions}.
     *
     * @param target The class to use when iterating over the results
     * @param <U>    type of the results
     * @return an iterator of the computed results
     * @mongodb.driver.manual reference/operator/aggregation/out $out
     */
    <U> Iterator<U> out(Class<U> target);

    /**
     * Places the output of the aggregation in the collection mapped by the target type.
     *
     * @param target  The class to use when iterating over the results
     * @param options The options to apply to this aggregation
     * @param <U>     type of the results
     * @return an iterator of the computed results
     * @mongodb.driver.manual reference/operator/aggregation/out $out
     */
    <U> Iterator<U> out(Class<U> target, AggregationOptions options);

    /**
     * Places the output of the aggregation in the collection mapped by the target type using the default options as defined in {@link
     * AggregationOptions}.
     *
     * @param collectionName The collection in which to store the results of the aggregation overriding the mapped value in target
     * @param target         The class to use when iterating over the results
     * @param <U>            type of the results
     * @return an iterator of the computed results
     * @mongodb.driver.manual reference/operator/aggregation/out $out
     */
    <U> Iterator<U> out(String collectionName, Class<U> target);

    /**
     * Places the output of the aggregation in the collection mapped by the target type.
     *
     * @param collectionName The collection in which to store the results of the aggregation overriding the mapped value in target
     * @param target         The class to use when iterating over the results
     * @param options        The options to apply to this aggregation
     * @param <U>            type of the results
     * @return an iterator of the computed results
     * @mongodb.driver.manual reference/operator/aggregation/out $out
     */
    <U> Iterator<U> out(String collectionName, Class<U> target, AggregationOptions options);

    /**
     * Reshapes each document in the stream, such as by adding new fields or removing existing fields. For each input document, outputs one
     * document.
     *
     * @param projections the projections to apply to this pipeline
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/project $project
     */
    AggregationPipeline project(Projection... projections);

    /**
     * Skips the first n documents where n is the specified skip number and passes the remaining documents unmodified to the pipeline. For
     * each input document, outputs either zero documents (for the first n documents) or one document (if after the first n documents).
     *
     * @param count the number of documents to skip
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/skip $skip
     */
    AggregationPipeline skip(int count);

    /**
     * Reorders the document stream by a specified sort key. Only the order changes; the documents remain unmodified. For each input
     * document, outputs one document.
     *
     * @param sorts the sorts to apply to this pipeline
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/sort $sort
     */
    AggregationPipeline sort(Sort... sorts);

    /**
     * Deconstructs an array field from the input documents to output a document for each element. Each output document replaces the array
     * with an element value. For each input document, outputs n documents where n is the number of array elements and can be zero for an
     * empty array.
     *
     * @param field the field to unwind
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/unwind $unwind
     */
    AggregationPipeline unwind(String field);

    /**
     * Deconstructs an array field from the input documents to output a document for each element. Each output document replaces the array
     * with an element value. For each input document, outputs n documents where n is the number of array elements and can be zero for an
     * empty array.
     *
     * @param field   the field to unwind
     * @param options unwind options
     * @return this
     */
    AggregationPipeline unwind(String field, UnwindOptions options);
}
