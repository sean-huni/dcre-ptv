package za.co.fnb.dcre.ptv;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * The BDD suite: business-language scenarios over the real job + CockroachDB.
 *
 * <p>No tag filter. CTV needs {@code not @endo} / {@code @endo} to split one feature
 * directory across two differently-configured contexts; PTV has one flow, so every
 * scenario in {@code features/} runs in the single context.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "za.co.fnb.dcre.ptv.bdd")
class CucumberSuiteTest {
}
