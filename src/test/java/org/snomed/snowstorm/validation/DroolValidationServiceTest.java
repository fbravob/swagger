package org.snomed.snowstorm.validation;

import io.kaicode.elasticvc.api.BranchService;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.ihtsdo.drools.response.InvalidContent;
import org.ihtsdo.drools.response.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.AbstractTest;
import org.snomed.snowstorm.TestConfig;
import org.snomed.snowstorm.core.data.domain.*;
import org.snomed.snowstorm.core.data.services.BranchMetadataKeys;
import org.snomed.snowstorm.core.data.services.ConceptService;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.snomed.snowstorm.core.data.domain.Concepts.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = TestConfig.class)
public class DroolValidationServiceTest extends AbstractTest {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static boolean checkoutRulesDone = false;

    private static final String DEFAULT_BRANCH = "MAIN";

    @Autowired
    private ConceptService conceptService;

    @Autowired
    private DroolsValidationService droolValidationService;

    @Autowired
    private BranchService branchService;

    @Value("${validation.drools.rules.path}")
    private String droolsRulesPath;

    @BeforeEach
    void setup() throws ServiceException, GitAPIException, IOException {
        checkoutDroolsRules();

        Map <String, String> metadata = new HashMap <>();
        metadata.put(BranchMetadataKeys.ASSERTION_GROUP_NAMES, "common-authoring");
        branchService.updateMetadata(DEFAULT_BRANCH, metadata);

        conceptService.create(new Concept(SNOMEDCT_ROOT, null, true, CORE_MODULE, PRIMITIVE), DEFAULT_BRANCH);
        conceptService.create(new Concept(ISA, null, true, CORE_MODULE, Concepts.PRIMITIVE), DEFAULT_BRANCH);

        String conceptId = "100001";
        Concept concept = new Concept(conceptId, null, true, CORE_MODULE, PRIMITIVE);
        concept.addDescription(new Description("12220000", null, true, CORE_MODULE, conceptId, "en", FSN, "Test (event)", Concepts.CASE_INSENSITIVE));
        concept.addDescription(new Description("12220003", null, true, CORE_MODULE, conceptId, "en", SYNONYM, "Test", CASE_INSENSITIVE));

        conceptService.create(concept, DEFAULT_BRANCH);
    }

    @Test
    void testValidateRegularConcept() throws ServiceException {
        Concept foundConcept = conceptService.find("100001", DEFAULT_BRANCH);
        assertNotNull(foundConcept);

        foundConcept.getDescriptions().stream().forEach(description -> description.setAcceptabilityMap(Collections.singletonMap(Concepts.US_EN_LANG_REFSET,
                Concepts.descriptionAcceptabilityNames.get(PREFERRED))));

        foundConcept.addRelationship(new Relationship("100002", ISA, SNOMEDCT_ROOT).setCharacteristicTypeId(Concepts.STATED_RELATIONSHIP));

        final Concept updatedConcept = conceptService.update(foundConcept, DEFAULT_BRANCH);
        assertEquals(1, updatedConcept.getRelationships().size());
        List <InvalidContent> invalidContents = droolValidationService.validateConcept(DEFAULT_BRANCH, updatedConcept);
        assertEquals(3, invalidContents.size());

        int index = 0;
        assertEquals(Severity.WARNING, invalidContents.get(index).getSeverity());
        assertEquals("Test resources were not available so assertions like case significance and US specific terms checks will not have run.", invalidContents.get(index).getMessage());

        index++;
        assertEquals(Severity.WARNING, invalidContents.get(index).getSeverity());
        assertEquals("Active FSN should end with a valid semantic tag.", invalidContents.get(index).getMessage());

        index++;
        assertEquals(Severity.WARNING, invalidContents.get(index).getSeverity());
        assertEquals("A concept's semantic tags should be compatible with those of the active parents.", invalidContents.get(index).getMessage());
    }

    @Test
    void testValidateConceptWithoutRelationship() throws ServiceException {
        Concept foundConcept = conceptService.find("100001", DEFAULT_BRANCH);
        assertNotNull(foundConcept);
        foundConcept.getDescriptions().stream().forEach(description -> description.setAcceptabilityMap(Collections.singletonMap(Concepts.US_EN_LANG_REFSET,
                Concepts.descriptionAcceptabilityNames.get(PREFERRED))));

        final Concept updatedConcept = conceptService.update(foundConcept, DEFAULT_BRANCH);

        assertEquals(0, updatedConcept.getRelationships().size());
        List <InvalidContent> invalidContents = droolValidationService.validateConcept(DEFAULT_BRANCH, updatedConcept);
        assertEquals(4, invalidContents.size());

        int index = 0;
        assertEquals(Severity.WARNING, invalidContents.get(index).getSeverity());
        assertEquals("Test resources were not available so assertions like case significance and US specific terms checks will not have run.", invalidContents.get(index).getMessage());

        index++;
        assertEquals(Severity.WARNING, invalidContents.get(index).getSeverity());
        assertEquals("Active FSN should end with a valid semantic tag.", invalidContents.get(index).getMessage());

        index++;
        assertEquals(Severity.ERROR, invalidContents.get(index).getSeverity());
        assertEquals("Active concepts must have at least one IS A relationship.", invalidContents.get(index).getMessage());

        index++;
        assertEquals(Severity.WARNING, invalidContents.get(index).getSeverity());
        assertEquals("A concept's semantic tags should be compatible with those of the active parents.", invalidContents.get(index).getMessage());
    }

    @Test
    void testValidateConceptAgainstConcreteValue() throws ServiceException {
        Concept foundConcept = conceptService.find("100001", DEFAULT_BRANCH);
        assertNotNull(foundConcept);

        foundConcept.getDescriptions().stream().forEach(description -> description.setAcceptabilityMap(Collections.singletonMap(Concepts.US_EN_LANG_REFSET,
                Concepts.descriptionAcceptabilityNames.get(PREFERRED))));

        foundConcept.addRelationship(new Relationship("100002", ISA, SNOMEDCT_ROOT).setCharacteristicTypeId(Concepts.STATED_RELATIONSHIP));

        ConcreteValue value = ConcreteValue.from("10", "int");
        foundConcept.addRelationship(new Relationship("100003", "23131313", value));

        final Concept updatedConcept = conceptService.update(foundConcept, DEFAULT_BRANCH);
        assertEquals(2, updatedConcept.getRelationships().size());

        List <InvalidContent> invalidContents = droolValidationService.validateConcept(DEFAULT_BRANCH, updatedConcept);
        assertEquals(3, invalidContents.size());

        int index = 0;
        assertEquals(Severity.WARNING, invalidContents.get(index).getSeverity());
        assertEquals("Test resources were not available so assertions like case significance and US specific terms checks will not have run.", invalidContents.get(index).getMessage());

        index++;
        assertEquals(Severity.WARNING, invalidContents.get(index).getSeverity());
        assertEquals("Active FSN should end with a valid semantic tag.", invalidContents.get(index).getMessage());

        index++;
        assertEquals(Severity.WARNING, invalidContents.get(index).getSeverity());
        assertEquals("A concept's semantic tags should be compatible with those of the active parents.", invalidContents.get(index).getMessage());
    }

    private void checkoutDroolsRules() throws GitAPIException, IOException {
        if (checkoutRulesDone) {
            return;
        }

        File localRepoDir = new File(droolsRulesPath);

        // Delete old rules if any
        if (localRepoDir.isDirectory() && localRepoDir.listFiles().length != 0) {
            FileUtils.deleteDirectory(localRepoDir);
        }

        logger.info("Cloning snomed-drools-rules repository...");
        TextProgressMonitor consoleProgressMonitor = new TextProgressMonitor(new PrintWriter(System.out));
        Repository repo = Git.cloneRepository().setProgressMonitor(consoleProgressMonitor).setDirectory(localRepoDir)
                .setURI("https://github.com/IHTSDO/snomed-drools-rules.git").call().getRepository();
        try (Git git = new Git(repo)) {
            git.checkout().setCreateBranch(true).setName("local-master").setStartPoint("master").call();
            git.getRepository().close();
        }
        droolValidationService.newRuleExecutorAndResources();
        checkoutRulesDone = true;
    }
}
