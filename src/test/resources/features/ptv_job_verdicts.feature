# Arrival-level semantics: the job's business verdict aggregates the
# per-record outcomes (BUSINESS_ACCEPTED / BUSINESS_PARTIAL), the structural
# tier rejects a spine that contradicts its header (R-19), and re-validation
# upserts keyed (arrival_id, sequence) so a replay never duplicates (R-05).
@ptv @job-verdicts
Feature: PTV arrival-level job verdicts and replay safety

  Scenario: An arrival where every payment passes is fully accepted
    Given a payments account "63030000000001" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    When PTV validates these payments as one arrival:
      | account        | contract  | amount | e2e |
      | 63030000000001 | CT-JOB-01 | 100.00 |     |
      | 63030000000001 | CT-JOB-01 | 200.00 |     |
    Then the job verdict is "BUSINESS_ACCEPTED"
    And record 1 is marked "PASS"
    And record 2 is marked "PASS"

  # The mixed arrival fails its second record on the CAP, not on account absence:
  # an unknown account passes through to PAI on payments, so CTV's
  # FAIL_ACCOUNT_NOT_FOUND row cannot produce a BUSINESS_PARTIAL here.
  Scenario: An arrival mixing passing and failing payments is partially accepted
    Given a payments account "63030000000002" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    And a payments account "63030000000012" with product "FNBRF", cap "50.00" and status "ACTIVE"
    When PTV validates these payments as one arrival:
      | account        | contract  | amount | e2e |
      | 63030000000002 | CT-JOB-02 | 100.00 |     |
      | 63030000000012 | CT-JOB-02 | 100.00 |     |
    Then the job verdict is "BUSINESS_PARTIAL"
    And record 1 is marked "PASS"
    And record 2 is marked "FAIL_EXCEEDS_RF_BALANCE"

  Scenario: Re-validating the same arrival leaves the validation log unchanged
    Given a payments account "63030000000003" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    When PTV validates these payments as one arrival:
      | account        | contract  | amount | e2e |
      | 63030000000003 | CT-JOB-03 | 100.00 |     |
      | 63030000000003 | CT-JOB-03 | 200.00 |     |
    And PTV validates the arrival again
    Then the job verdict is "BUSINESS_ACCEPTED"
    And the validation log holds exactly 2 verdicts for the arrival

  Scenario: A spine contradicting the header count is rejected file-fatally
    When PTV validates an arrival whose header declares 3 transactions but whose spine carries 2
    Then the arrival is rejected file-fatally with a reason containing "spine count 2 != declared 3"
