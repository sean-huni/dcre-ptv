# PTV validates every ENDO payment read by PRR against the DCRE account store
# before the record proceeds down the pipeline.
#
# SCRUM-107 repair 1: the account tier fails CLOSED. An account the reference
# store does not hold is rejected with FAIL_ACCOUNT_NOT_FOUND, not passed through.
# The A-20 draft passed it on the grounds that PAI would create it downstream
# (create-if-absent, R-11), which left the tier answering PASS in precisely the
# case it exists to catch. A KNOWN account whose cap is unset still passes: the
# row exists and only the cap is missing, which is a different question.
#
# "Absent" and "could not be read" are different outcomes and must stay so. This
# feature covers the business half only; an unreadable reference store halts the
# job with no verdict at all (AccountStoreUnavailableIT).
#
# There is no mandate scenario. Payments carry no bank-registered mandates and
# therefore no mandate gate (SPEC-ENDO-COLLECTIONS-FLOW.md).
@ptv @account-validation
Feature: PTV account-level validation of inbound payment requests

  Scenario: Payment against an existing FNBRF account within balance
    Given a payments account "63010000000001" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    When PTV validates a payment of "150.00" against account "63010000000001" under contract "CT-ACC-01"
    Then the record is marked valid with outcome "PASS"

  Scenario: Payment amount exactly equal to the account cap
    Given a payments account "63010000000002" with product "FNBRF", cap "300.00" and status "ACTIVE"
    When PTV validates a payment of "300.00" against account "63010000000002" under contract "CT-ACC-02"
    Then the record is marked valid with outcome "PASS"

  Scenario: Payment against an account absent from the DCRE account store is rejected
    When PTV validates a payment of "100.00" against account "63019999999901"
    Then the record is rejected with outcome "FAIL_ACCOUNT_NOT_FOUND"
    And the job verdict is "BUSINESS_PARTIAL"

  Scenario: Payment against an account that is not ACTIVE
    Given a payments account "63010000000003" with product "FNBRF", cap "5000.00" and status "SUSPENDED"
    When PTV validates a payment of "100.00" against account "63010000000003"
    Then the record is rejected with outcome "FAIL_ACCOUNT_NOT_ACTIVE"

  Scenario: Payment exceeding an existing FNBRF account balance still fails
    Given a payments account "63010000000004" with product "FNBRF", cap "100.00" and status "ACTIVE"
    When PTV validates a payment of "250.00" against account "63010000000004"
    Then the record is rejected with outcome "FAIL_EXCEEDS_RF_BALANCE"
    And the job verdict is "BUSINESS_PARTIAL"

  Scenario: Payment exceeding an existing FNBCC credit limit still fails
    Given a payments account "63010000000005" with product "FNBCC", cap "1000.00" and status "ACTIVE"
    When PTV validates a payment of "1500.00" against account "63010000000005"
    Then the record is rejected with outcome "FAIL_EXCEEDS_CC_LIMIT"

  Scenario: Payment against a known account with no recorded cap passes on the cap tier
    Given a payments account "63010000000006" with product "FNBCC", cap "none" and status "ACTIVE"
    When PTV validates a payment of "100.00" against account "63010000000006"
    Then the record is marked valid with outcome "PASS"

  Scenario: Payment reusing an EndToEndId already seen in the file
    Given a payments account "63010000000007" with product "FNBRF", cap "5000.00" and status "ACTIVE"
    When PTV validates these payments as one arrival:
      | account        | contract  | amount | e2e          |
      | 63010000000007 | CT-ACC-07 | 100.00 | E2E-DUP-0001 |
      | 63010000000007 | CT-ACC-07 | 120.00 | E2E-DUP-0001 |
    Then record 1 is marked "PASS"
    And record 2 is marked "FAIL_DUPLICATE_E2E"
