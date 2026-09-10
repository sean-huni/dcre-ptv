package za.co.fnb.dcre.ptv.bdd;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.ptv.PtvTestTables;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step definitions for the payments verdict chain. One glue package: CTV splits
 * these across {@code bdd} (DC) and {@code bddendo} (ENDO) because one service
 * serves two families; PTV has one.
 */
public class PtvSteps {

    @Autowired
    Job ptvJob;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JdbcTemplate jdbc;

    UUID arrival;
    JobExecution execution;
    int seq;

    @Before
    public void freshArrival() {
        PtvTestTables.create(jdbc);
        arrival = UUID.randomUUID();
        seq = 0;
    }

    @Given("a payments account {string} with product {string}, cap {string} and status {string}")
    public void account(String number, String product, String cap, String status) {
        PtvTestTables.insertAccount(jdbc, number, product, cap, status);
    }

    /**
     * The distinction SCRUM-107 repair 2 exists to make: the reference store HAS been loaded
     * and holds accounts, so an account missing from it is a business rejection. A scenario
     * that seeds no account at all is describing an unloaded database, which halts the job
     * technically and produces no verdict to assert.
     */
    @Given("the account reference store has been loaded with other accounts")
    public void referenceStoreLoaded() {
        PtvTestTables.materialiseReference(jdbc);
    }

    @When("PTV validates a payment of {string} against account {string} under contract {string}")
    public void validateSingle(String amount, String account, String contract) throws Exception {
        addEntry(account, contract, amount, null);
        PtvTestTables.insertHeader(jdbc, arrival, seq);
        run(null);
    }

    @When("PTV validates a payment of {string} against account {string}")
    public void validateSingleNoContract(String amount, String account) throws Exception {
        addEntry(account, null, amount, null);
        PtvTestTables.insertHeader(jdbc, arrival, seq);
        run(null);
    }

    @When("PTV validates these payments as one arrival:")
    public void validateArrival(DataTable table) throws Exception {
        for (Map<String, String> row : table.asMaps()) {
            addEntry(row.get("account"), row.get("contract"), row.get("amount"), row.get("e2e"));
        }
        PtvTestTables.insertHeader(jdbc, arrival, seq);
        run(null);
    }

    @When("PTV validates the arrival again")
    public void validateAgain() throws Exception {
        run("2");
    }

    @When("PTV validates an arrival whose header declares {int} transactions but whose spine carries {int}")
    public void validateCountMismatch(int declared, int carried) throws Exception {
        for (int i = 0; i < carried; i++) {
            addEntry("63999999999901", null, "10.00", null);
        }
        PtvTestTables.insertHeader(jdbc, arrival, declared);
        run(null);
    }

    @Then("the record is marked valid with outcome {string}")
    public void recordValid(String outcome) {
        recordMarked(1, outcome);
    }

    @Then("the record is rejected with outcome {string}")
    public void recordRejected(String outcome) {
        recordMarked(1, outcome);
    }

    @Then("record {int} is marked {string}")
    public void recordMarked(int sequence, String outcome) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(outcome, jdbc.queryForObject(
                "SELECT outcome FROM validation_log WHERE arrival_id=? AND sequence=?",
                String.class, arrival, sequence));
    }

    @Then("the job verdict is {string}")
    public void jobVerdict(String verdict) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(verdict, execution.getExecutionContext().getString("ptvVerdict"));
    }

    @Then("the arrival is rejected file-fatally with a reason containing {string}")
    public void fileFatal(String reasonPart) {
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        String reason = execution.getExecutionContext().getString("fileFatalReason");
        assertTrue(reason.contains(reasonPart),
                "expected file-fatal reason containing '" + reasonPart + "' but was: " + reason);
    }

    @Then("the validation log holds exactly {int} verdicts for the arrival")
    public void verdictCount(int expected) {
        assertEquals(expected, jdbc.queryForObject(
                "SELECT count(*) FROM validation_log WHERE arrival_id=?", Integer.class, arrival));
    }

    void addEntry(String account, String contract, String amount, String e2e) {
        seq++;
        String endToEnd = e2e == null || e2e.isBlank()
                ? "E2E-" + arrival.toString().substring(0, 8) + "-" + seq : e2e;
        PtvTestTables.insertEntry(jdbc, arrival, seq, endToEnd, account, contract, amount);
    }

    void run(String attempt) throws Exception {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true);
        if (attempt != null) {
            builder.addString("attempt", attempt, true);
        }
        execution = jobOperator.start(ptvJob, builder.toJobParameters());
    }
}
