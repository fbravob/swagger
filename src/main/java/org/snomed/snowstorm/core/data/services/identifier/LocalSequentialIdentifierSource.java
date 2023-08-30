package org.snomed.snowstorm.core.data.services.identifier;

import org.elasticsearch.script.Script;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.ScriptSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.snomed.snowstorm.core.data.domain.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;

import java.util.*;

import static org.elasticsearch.index.query.QueryBuilders.*;

/**
 * Generates SNOMED Component identifiers locally using sequential identifiers.
 * The store is queried to find the largest sequence number.
 * Assumes that SCTIDs in the same sequence are not being generated by other services/applications, otherwise identifier collision is likely.
 */
public class LocalSequentialIdentifierSource implements IdentifierSource {

	private final ElasticsearchRestTemplate elasticsearchTemplate;
	private final Map<String, Integer> namespaceAndPartitionHighestSequenceCache = Collections.synchronizedMap(new HashMap<>());

	public LocalSequentialIdentifierSource(ElasticsearchRestTemplate elasticsearchTemplate) {
		this.elasticsearchTemplate = elasticsearchTemplate;
	}

	@Override
	public List<Long> reserveIds(int namespaceId, String partitionId, int quantity) {
		List<Long> newIdentifiers = new ArrayList<>();
		int sequence = findHighestIdentifierSequence(namespaceId, partitionId);

		synchronized (namespaceAndPartitionHighestSequenceCache) {
			// Take sequence from cache if it's higher. This is possible if ids have been reserved but not yet persisted in records
			String sequenceCacheKey = namespaceId + "_" + partitionId;
			int sequenceCacheValue = namespaceAndPartitionHighestSequenceCache.getOrDefault(sequenceCacheKey, 0);
			if (sequenceCacheValue > sequence) {
				sequence = sequenceCacheValue;
			}

			for (int i = 0; i < quantity; i++) {
				sequence++;

				String namespace = namespaceId == 0 ? "" : namespaceId + "";
				String sctidWithoutCheck = sequence + namespace + partitionId;
				char verhoeff = VerhoeffCheck.calculateChecksum(sctidWithoutCheck, 0, false);
				long newSctid = Long.parseLong(sctidWithoutCheck + verhoeff);
				newIdentifiers.add(newSctid);
			}

			namespaceAndPartitionHighestSequenceCache.put(sequenceCacheKey, sequence);
		}

		return newIdentifiers;
	}

	/**
	 * Finds highest SCTID in the given namespace and partition across all branches and versions. Returns the sequence identifier.
	 * @return Sequence from the highest existing SCTID.
	 */
	private int findHighestIdentifierSequence(int namespaceId, String partitionId) {

		Class<? extends SnomedComponent<?>> componentClass = null;
		String idField = null;

		switch (partitionId) {
			case "00":
			case "10":
				// Concept identifier
				componentClass = Concept.class;
				idField = Concept.Fields.CONCEPT_ID;
				break;
			case "01":
			case "11":
				// Description identifier
				componentClass = Description.class;
				idField = Description.Fields.DESCRIPTION_ID;
				break;
			case "02":
			case "12":
				// Relationship identifier
				componentClass = Relationship.class;
				idField = Relationship.Fields.RELATIONSHIP_ID;
				break;
			case "16":
				// Expression identifier
				componentClass = ReferenceSetMember.class;
				idField = ReferenceSetMember.Fields.REFERENCED_COMPONENT_ID;
				break;
		}
		if (idField == null) {
			throw new IllegalStateException(String.format("Partition '%s' is not handled by the configured identifier generator.", partitionId));
		}

		String regex;
		if (namespaceId == 0) {
			// International
			// Restricting sequence length avoids matching extensions and long International model ids like "900000000000550004"
			regex = String.format("[0-9]{0,11}%s[0-9]", partitionId);
		} else {
			// Extension
			regex = String.format("[0-9]*%s%s[0-9]", namespaceId, partitionId);
		}

		SearchHits<? extends SnomedComponent<?>> searchHits = elasticsearchTemplate.search(new NativeSearchQueryBuilder()

				// Regex query
				.withQuery(regexpQuery(idField, regex))

				// Numbers as strings require sorting by length and characters:
				// Sort by string length first..
				.withSort(new ScriptSortBuilder(new Script(String.format("doc['%s'].value.length()", idField)), ScriptSortBuilder.ScriptSortType.NUMBER).order(SortOrder.DESC))
				// then sort by characters.
				.withSort(new FieldSortBuilder(idField).order(SortOrder.DESC))

				// One item
				.withPageable(PageRequest.of(0, 1))

				.build(), componentClass);

		int highestSequence = 0;
		if (searchHits.hasSearchHits()) {
			SearchHit<? extends SnomedComponent<?>> searchHit = searchHits.getSearchHit(0);
			SnomedComponent<?> component = searchHit.getContent();
			String highestSctid;
			if (idField.equals(ReferenceSetMember.Fields.REFERENCED_COMPONENT_ID)) {
				highestSctid = ((ReferenceSetMember) component).getReferencedComponentId();
			} else {
				highestSctid = component.getId();
			}
			if (namespaceId == 0) {
				highestSequence = Integer.parseInt(highestSctid.substring(0, highestSctid.length() - 3));
			} else {
				highestSequence = Integer.parseInt(highestSctid.substring(0, highestSctid.lastIndexOf(namespaceId + "")));
			}
		}
		return highestSequence;
	}

	@Override
	public void registerIds(int namespace, Collection<Long> idsAssigned) {
		// Not required for this implementation.
	}

}
