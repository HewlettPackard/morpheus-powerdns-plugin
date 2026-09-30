package com.morpheusdata.powerdns

import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkDomainRecord
import com.morpheusdata.response.ServiceResponse
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

/**
 * Exercises {@link PowerDnsApiClient} against a real, reachable Power DNS instance - no mocks. See
 * src/functionalTest/README.md for how to stand up a Power DNS server and configure the environment
 * variables these tests read. Skipped automatically (rather than failing) when POWERDNS_TEST_URL and
 * POWERDNS_TEST_API_KEY are not both set, so `functionalTest` is safe to run in environments without
 * a Power DNS server available.
 */
@IgnoreIf({ !System.getenv('POWERDNS_TEST_URL') || !System.getenv('POWERDNS_TEST_API_KEY') })
class PowerDnsApiClientFunctionalSpec extends Specification {

    @Shared
    String zoneName = System.getenv('POWERDNS_TEST_ZONE') ?: 'example.com.'

    @Shared
    PowerDnsApiClient apiClient

    @Shared
    NetworkDomain domain

    def setupSpec() {
        AccountIntegration integration = new AccountIntegration(
                name: 'Functional Test PowerDNS',
                serviceUrl: System.getenv('POWERDNS_TEST_URL'),
                servicePassword: System.getenv('POWERDNS_TEST_API_KEY'),
                serviceVersion: System.getenv('POWERDNS_TEST_SERVICE_VERSION') ?: '4'
        )
        apiClient = new PowerDnsApiClient(integration)
        domain = new NetworkDomain(name: zoneName, externalId: "servers/localhost/zones/${zoneName}".toString())
    }

    def cleanupSpec() {
        apiClient?.shutdown()
    }

    def "listZones returns the configured test zone"() {
        when:
        ServiceResponse results = apiClient.listZones()

        then:
        results.success
        results.data.find { it.name == zoneName }
    }

    def "createRecord, doesRecordExist, and deleteRecord round-trip against the live server"() {
        given:
        NetworkDomainRecord record = new NetworkDomainRecord(
                type: 'A', fqdn: "functest.${zoneName}".toString(), name: 'functest', content: '203.0.113.5', ttl: 300, networkDomain: domain
        )

        expect: 'the record does not already exist'
        !apiClient.doesRecordExist(record)

        when: 'the record is created'
        ServiceResponse createResult = apiClient.createRecord(record, false)

        then:
        createResult.success

        and: 'it is now visible on the live zone'
        apiClient.doesRecordExist(record)

        when: 'the record is deleted'
        ServiceResponse deleteResult = apiClient.deleteRecord(record)

        then:
        deleteResult.success
        !apiClient.doesRecordExist(record)
    }
}
