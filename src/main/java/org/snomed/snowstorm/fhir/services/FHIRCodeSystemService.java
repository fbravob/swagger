package org.snomed.snowstorm.fhir.services;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.domain.CodeSystemVersion;
import org.snomed.snowstorm.core.data.domain.Concept;
import org.snomed.snowstorm.core.data.services.CodeSystemService;
import org.snomed.snowstorm.core.data.services.ConceptService;
import org.snomed.snowstorm.core.data.services.MultiSearchService;
import org.snomed.snowstorm.core.data.services.NotFoundException;
import org.snomed.snowstorm.core.data.services.pojo.ConceptCriteria;
import org.snomed.snowstorm.core.pojo.LanguageDialect;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.snomed.snowstorm.fhir.pojo.ConceptAndSystemResult;
import org.snomed.snowstorm.fhir.pojo.FHIRCodeSystemVersionParams;
import org.snomed.snowstorm.fhir.repositories.FHIRCodeSystemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static java.lang.String.format;

@Service
public class FHIRCodeSystemService {

	public static final String SCT_ID_PREFIX = "sct_";

	@Autowired
	private FHIRCodeSystemRepository codeSystemRepository;

	@Autowired
	private CodeSystemService snomedCodeSystemService;

	@Autowired
	private ConceptService conceptService;

	@Autowired
	private MultiSearchService snomedMultiSearchService;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public FHIRCodeSystemVersion save(CodeSystem codeSystem) throws FHIROperationException {
		FHIRCodeSystemVersion fhirCodeSystemVersion = new FHIRCodeSystemVersion(codeSystem);

		// Prevent saving SNOMED CT this way
		if (fhirCodeSystemVersion.getId().startsWith(SCT_ID_PREFIX)) {
			throw new FHIROperationException(OperationOutcome.IssueType.NOTSUPPORTED, format("Code System id prefix '%s' is reserved for SNOMED CT code system. " +
					"Please save these via the native API RF2 import function.", SCT_ID_PREFIX));
		}

		wrap(fhirCodeSystemVersion);
		logger.info("Saving fhir code system '{}'.", fhirCodeSystemVersion.getId());
		codeSystemRepository.save(fhirCodeSystemVersion);
		return fhirCodeSystemVersion;
	}

	public FHIRCodeSystemVersion findCodeSystemVersionOrThrow(FHIRCodeSystemVersionParams systemVersionParams) throws FHIROperationException {
		FHIRCodeSystemVersion codeSystemVersion = findCodeSystemVersion(systemVersionParams);
		if (codeSystemVersion == null) {
			throw new FHIROperationException(OperationOutcome.IssueType.NOTFOUND, format("Code system not found for parameters %s.", systemVersionParams));
		}
		return codeSystemVersion;
	}

	public FHIRCodeSystemVersion findCodeSystemVersion(FHIRCodeSystemVersionParams systemVersionParams) {
		// TODO: use version in param
		FHIRCodeSystemVersion version;
		String id = systemVersionParams.getId();
		String versionParam = systemVersionParams.getVersion();
		if (id != null) {
			version = codeSystemRepository.findById(id).orElse(null);
		} else if (versionParam != null) {
			version = codeSystemRepository.findByUrlAndVersion(systemVersionParams.getCodeSystem(), versionParam);
		} else {
			version = codeSystemRepository.findFirstByUrlOrderByVersionDesc(systemVersionParams.getCodeSystem());
		}
		unwrap(version);
		return version;
	}

	public FHIRCodeSystemVersion getSnomedVersion(FHIRCodeSystemVersionParams codeSystemVersion) throws FHIROperationException {
		if (!codeSystemVersion.isSnomed()) {
			throw new FHIROperationException(OperationOutcome.IssueType.CONFLICT, "Failed to find SNOMED branch for non SCT code system.");
		}

		org.snomed.snowstorm.core.data.domain.CodeSystem snomedCodeSystem;
		String snomedModule = codeSystemVersion.getSnomedModule();
		if (snomedModule != null) {
			snomedCodeSystem = snomedCodeSystemService.findByDefaultModule(snomedModule);
		} else {
			// Use root code system
			snomedCodeSystem = snomedCodeSystemService.find(CodeSystemService.SNOMEDCT);
		}
		if (codeSystemVersion.isUnversionedSnomed()) {
			// Use working branch
			return new FHIRCodeSystemVersion(snomedCodeSystem, true);
		} else {
			String shortName = snomedCodeSystem.getShortName();
			String version = codeSystemVersion.getVersion();
			CodeSystemVersion snomedVersion;
			if (version == null) {
				// Use the latest published branch
				snomedVersion = snomedCodeSystemService.findLatestVisibleVersion(shortName);
				if (snomedVersion == null) {
					// Fall back to any imported version
					snomedVersion = snomedCodeSystemService.findLatestImportedVersion(shortName);
				}
			} else {
				snomedVersion = snomedCodeSystemService.findVersion(shortName, Integer.parseInt(version));
			}
			if (snomedVersion == null) {
				throw new FHIROperationException(OperationOutcome.IssueType.NOTFOUND, "Code system not found.");
			}
			snomedVersion.setCodeSystem(snomedCodeSystem);
			return new FHIRCodeSystemVersion(snomedVersion);
		}
	}

	private void wrap(FHIRCodeSystemVersion fhirCodeSystemVersion) {
		if (fhirCodeSystemVersion.getVersion() == null) {
			fhirCodeSystemVersion.setVersion("");
		}
	}

	private void unwrap(FHIRCodeSystemVersion version) {
		if (version != null && "".equals(version.getVersion())) {
			version.setVersion(null);
		}
	}

	public Iterable<FHIRCodeSystemVersion> findAll() {
		return codeSystemRepository.findAll();
	}

	public Optional<FHIRCodeSystemVersion> findById(String id) {
		return codeSystemRepository.findById(id);
	}

	public ConceptAndSystemResult findSnomedConcept(String code, List<LanguageDialect> languageDialects, FHIRCodeSystemVersionParams codeSystemParams)
			throws FHIROperationException {

		Concept concept;
		FHIRCodeSystemVersion codeSystemVersion;
		if (codeSystemParams.isUnspecifiedReleasedSnomed()) {
			// Multi-search is expensive, so we'll try on default branch first
			codeSystemVersion = getSnomedVersion(codeSystemParams);
			concept = conceptService.find(code, languageDialects, codeSystemVersion.getSnomedBranch());
			if (concept == null) {
				// Multi-search
				ConceptCriteria criteria = new ConceptCriteria().conceptIds(Collections.singleton(code));
				List<Concept> content = snomedMultiSearchService.findConcepts(criteria, PageRequest.of(0, 1)).getContent();
				if (!content.isEmpty()) {
					Concept bareConcept = content.get(0);
					// Recover published version where this concept was found
					CodeSystemVersion systemVersion = snomedMultiSearchService.getNearestPublishedVersion(bareConcept.getPath());
					if (systemVersion != null) {
						codeSystemVersion = new FHIRCodeSystemVersion(systemVersion);
						// Load whole concept for this code
						concept = conceptService.find(code, languageDialects, codeSystemVersion.getSnomedBranch());
					}
				}
			}
		} else {
			codeSystemVersion = getSnomedVersion(codeSystemParams);
			concept = conceptService.find(code, languageDialects, codeSystemVersion.getSnomedBranch());
		}
		return new ConceptAndSystemResult(concept, codeSystemVersion);
	}
}
