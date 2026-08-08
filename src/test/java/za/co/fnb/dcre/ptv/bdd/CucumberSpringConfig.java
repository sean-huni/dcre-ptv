package za.co.fnb.dcre.ptv.bdd;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One BDD context, not two. CTV runs a DC suite and a separate {@code @endo} suite
 * because one service serves two families out of one database and the two need
 * different Spring properties; PTV serves payments only, so there is one glue
 * package, one context, and no tag filter to keep them apart.
 *
 * <p>No mandates datasource property is registered, and none can be: PtvApplication
 * does not import {@code MandatesDatasourceConfig}, so no bean in this context can
 * open a dcre_man connection. CTV's ENDO suites have to point a closed port at it
 * to prove the same thing.
 */
@CucumberContextConfiguration
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "dcre.exchange-root=build/test-exchange",
        // R-41 acceptance-mode fixture: FNBRF01 overridden to PARTIAL, every
        // other client (e.g. FNBCC01) falls to the ALL_OR_NOTHING default.
        "dcre.ptv.acceptance-mode.clients.[FNBRF01]=PARTIAL"})
public class CucumberSpringConfig {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
    }
}
