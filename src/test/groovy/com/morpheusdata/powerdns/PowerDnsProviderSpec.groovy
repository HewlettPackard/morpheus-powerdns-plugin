package com.morpheusdata.powerdns

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.NetworkDomainRecord
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

/**
 * Unit tests for {@link PowerDnsProvider}'s orchestration logic. The {@link PowerDnsApiClient} is stubbed out
 * via the {@code createApiClient} factory seam so these tests verify decision-making (duplicate checks, response
 * translation) without making real HTTP calls. See src/test/README.md for the overall test strategy.
 */
class PowerDnsProviderSpec extends Specification {

    MorpheusContext morpheusContext = Mock(MorpheusContext)
    Plugin plugin = Mock(Plugin)
    PowerDnsApiClient apiClient = Mock(PowerDnsApiClient)

    private AccountIntegration integration(Map overrides = [:]) {
        Map defaults = [name: 'Test PowerDNS', serviceUrl: 'https://pdns.example.com:8081']
        return new AccountIntegration(defaults + overrides)
    }

    private NetworkDomainRecord record(Map overrides = [:]) {
        Map defaults = [type: 'A', fqdn: 'www.example.com.', name: 'www', content: '10.0.0.1', ttl: 300]
        return new NetworkDomainRecord(defaults + overrides)
    }

    private PowerDnsProvider providerWithStubbedApiClient() {
        PowerDnsProvider provider = Spy(constructorArgs: [plugin, morpheusContext])
        provider.createApiClient(_) >> apiClient
        return provider
    }

    def "createRecord returns an error and does not call create when the record already exists in Power DNS"() {
        given:
        PowerDnsProvider provider = providerWithStubbedApiClient()
        NetworkDomainRecord domainRecord = record()

        when:
        ServiceResponse result = provider.createRecord(integration(), domainRecord, [:])

        then:
        1 * apiClient.doesRecordExist(domainRecord) >> true
        0 * apiClient.createRecord(*_)
        1 * apiClient.shutdown()
        !result.success
        result.msg.contains('already exists')
    }

    def "createRecord creates the record and sets externalId when it does not already exist"() {
        given:
        PowerDnsProvider provider = providerWithStubbedApiClient()
        NetworkDomainRecord domainRecord = record()

        when:
        ServiceResponse result = provider.createRecord(integration(), domainRecord, [:])

        then:
        1 * apiClient.doesRecordExist(domainRecord) >> false
        1 * apiClient.createRecord(domainRecord, true) >> ServiceResponse.success()
        1 * apiClient.shutdown()
        result.success
        result.data.externalId == 'A:www'
    }

    def "createRecord honors serviceFlag == false by not requesting a PTR record"() {
        given:
        PowerDnsProvider provider = providerWithStubbedApiClient()
        NetworkDomainRecord domainRecord = record()

        when:
        provider.createRecord(integration(serviceFlag: false), domainRecord, [:])

        then:
        1 * apiClient.doesRecordExist(domainRecord) >> false
        1 * apiClient.createRecord(domainRecord, false) >> ServiceResponse.success()
    }

    def "createRecord returns an error response when Power DNS rejects the create"() {
        given:
        PowerDnsProvider provider = providerWithStubbedApiClient()
        NetworkDomainRecord domainRecord = record()

        when:
        ServiceResponse result = provider.createRecord(integration(), domainRecord, [:])

        then:
        1 * apiClient.doesRecordExist(domainRecord) >> false
        1 * apiClient.createRecord(domainRecord, true) >> ServiceResponse.error('bad request')
        1 * apiClient.shutdown()
        !result.success
        result.msg.contains('bad request')
    }

    def "deleteRecord returns success when Power DNS confirms the delete"() {
        given:
        PowerDnsProvider provider = providerWithStubbedApiClient()
        NetworkDomainRecord domainRecord = record()

        when:
        ServiceResponse result = provider.deleteRecord(integration(), domainRecord, [:])

        then:
        1 * apiClient.deleteRecord(domainRecord) >> ServiceResponse.success()
        1 * apiClient.shutdown()
        result.success
    }

    def "deleteRecord returns an error response when Power DNS rejects the delete"() {
        given:
        PowerDnsProvider provider = providerWithStubbedApiClient()
        NetworkDomainRecord domainRecord = record()

        when:
        ServiceResponse result = provider.deleteRecord(integration(), domainRecord, [:])

        then:
        1 * apiClient.deleteRecord(domainRecord) >> ServiceResponse.error('not found')
        1 * apiClient.shutdown()
        !result.success
        result.error.contains('not found')

    }

    def "deleteRecord returns a system error response when the api client throws"() {
        given:
        PowerDnsProvider provider = providerWithStubbedApiClient()
        NetworkDomainRecord domainRecord = record()

        when:
        ServiceResponse result = provider.deleteRecord(integration(), domainRecord, [:])

        then:
        1 * apiClient.deleteRecord(domainRecord) >> { throw new RuntimeException('boom') }
        1 * apiClient.shutdown()
        !result.success
        result.error.contains('boom')
    }
}
