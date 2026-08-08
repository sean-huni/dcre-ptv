# R-41 per-client acceptance mode: ALL_OR_NOTHING rejects the whole file on
# any business FAIL (NACK, BUSINESS_FILE_REJECTED); PARTIAL fails rows
# individually and lets the file proceed (ACK-with-partials). The mode is
# resolved from the header's InitgPty client token; the per-client override
# map lives in configuration (dcre.ptv.acceptance-mode.clients).
@ptv @acceptance-mode
Feature: PTV per-client acceptance mode on business failures

  Scenario: ALL_OR_NOTHING client: one duplicate rejects the whole file
    Given client "FNBCC01" has acceptance mode ALL_OR_NOTHING
    And an arrival for "FNBCC01" whose spine contains a content-duplicate pair
    When the PTV job runs for the arrival
    Then the PTV job exit status is "BUSINESS_FILE_REJECTED"
    And the duplicate row has verdict FAIL_DUPLICATE_TX

  Scenario: PARTIAL client: duplicates fail individually, file proceeds
    Given client "FNBRF01" has acceptance mode PARTIAL
    And an arrival for "FNBRF01" whose spine contains a content-duplicate pair
    When the PTV job runs for the arrival
    Then the PTV job exit status is "BUSINESS_PARTIAL"
    And the duplicate row has verdict FAIL_DUPLICATE_TX

  Scenario: clean file is accepted regardless of mode
    Given client "FNBCC01" has acceptance mode ALL_OR_NOTHING
    And an arrival for "FNBCC01" whose spine is clean
    When the PTV job runs for the arrival
    Then the PTV job exit status is "BUSINESS_ACCEPTED"
