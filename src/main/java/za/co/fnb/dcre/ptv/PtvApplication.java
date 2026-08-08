package za.co.fnb.dcre.ptv;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import za.co.fnb.dcre.platform.batch.ExitCodeMain;
import za.co.fnb.dcre.platform.batch.config.BatchJdbcConfig;
import za.co.fnb.dcre.platform.batch.config.HeartbeatDatasourceConfig;
import za.co.fnb.dcre.platform.persistence.JdbcConfig;

/**
 * PTV: "Validates TxHeader &amp; TxEntries" on the payments sheet, second stage of
 * PRR -&gt; PTV -&gt; PAI -&gt; {PRW, PIR}.
 *
 * <p>Deliberately NO mandates datasource. CTV imports {@code MandatesDatasourceConfig}
 * because the DC mandate gate is a real read of {@code dcre_man.man_ctv_view} (R-19).
 * ENDO carries no bank-registered mandates and therefore no mandate gate at all
 * (R-20; SPEC-ENDO-COLLECTIONS-FLOW.md: "No mandates, no mandate gate"), so importing
 * it here would open a cross-context connection to the mandates database for a
 * snapshot nothing reads, and would make payments unavailable whenever mandates is.
 * That coupling is precisely what the payments split removes.
 */
@SpringBootApplication
@Import({JdbcConfig.class, BatchJdbcConfig.class, HeartbeatDatasourceConfig.class})
public class PtvApplication {

    public static void main(String[] args) {
        ExitCodeMain.run(PtvApplication.class, args);
    }
}
