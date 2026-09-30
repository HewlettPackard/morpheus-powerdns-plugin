package com.morpheusdata.powerdns

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkDomainRecord
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for {@link PowerDnsApiClient}. The underlying {@link HttpApiClient} is mocked so these tests run
 * fully offline and verify the request/response mechanics (URLs, paths, auth headers, payload shape, response
 * parsing) without needing a real Power DNS server. See src/test/README.md for the overall test strategy.
 */
class PowerDnsApiClientSpec extends Specification {

    HttpApiClient client = Mock(HttpApiClient)

    private AccountIntegration integration(String serviceVersion = '4', Map overrides = [:]) {
        Map defaults = [
                name          : 'Test PowerDNS',
                serviceUrl    : 'https://pdns.example.com:8081',
                serviceVersion: serviceVersion,
                servicePassword: 'super-secret'
        ]
        return new AccountIntegration(defaults + overrides)
    }

    private NetworkDomainRecord record(Map overrides = [:]) {
        NetworkDomain domain = new NetworkDomain(externalId: 'servers/localhost/zones/example.com.')
        Map defaults = [type: 'A', fqdn: 'www.example.com.', name: 'www', content: '10.0.0.1', ttl: 300, networkDomain: domain]
        return new NetworkDomainRecord(defaults + overrides)
    }

    def "createRecord sends a PATCH with a REPLACE rrset built from the record"() {
        given:
        PowerDnsApiClient apiClient = new PowerDnsApiClient(integration(), client)

        when:
        apiClient.createRecord(record(), true)

        then:
        1 * client.callJsonApi('https://pdns.example.com:8081', '/servers/localhost/zones/example.com.', null, null, { HttpApiClient.RequestOptions opts ->
            opts.headers['X-API-KEY'] == 'super-secret' &&
                    opts.body.rrsets[0].changetype == 'REPLACE' &&
                    opts.body.rrsets[0].name == 'www.example.com.' &&
                    opts.body.rrsets[0].type == 'A' &&
                    opts.body.rrsets[0].records[0].content == '10.0.0.1'
        }, 'PATCH') >> ServiceResponse.success()
    }

    def "deleteRecord sends a PATCH with a DELETE rrset built from the record"() {
        given:
        PowerDnsApiClient apiClient = new PowerDnsApiClient(integration(), client)

        when:
        apiClient.deleteRecord(record())

        then:
        1 * client.callJsonApi('https://pdns.example.com:8081', '/servers/localhost/zones/example.com.', null, null, { HttpApiClient.RequestOptions opts ->
            opts.body.rrsets[0].changetype == 'DELETE' &&
                    opts.body.rrsets[0].name == 'www.example.com.'
        }, 'PATCH') >> ServiceResponse.success()
    }

    def "doesRecordExist returns true when a matching name and type is found on the live zone"() {
        given:
        PowerDnsApiClient apiClient = new PowerDnsApiClient(integration(), client)

        when:
        boolean exists = apiClient.doesRecordExist(record())

        then:
        1 * client.callJsonApi(*_) >> new ServiceResponse(true, null, null, [rrsets: [[name: 'www.example.com.', type: 'A']]])
        exists
    }

    def "doesRecordExist returns false when no record matches name and type"() {
        given:
        PowerDnsApiClient apiClient = new PowerDnsApiClient(integration(), client)

        when:
        boolean exists = apiClient.doesRecordExist(record())

        then:
        1 * client.callJsonApi(*_) >> new ServiceResponse(true, null, null, [rrsets: [[name: 'other.example.com.', type: 'A'], [name: 'www.example.com.', type: 'CNAME']]])
        !exists
    }

    def "doesRecordExist returns false and does not throw when the live lookup fails"() {
        given:
        PowerDnsApiClient apiClient = new PowerDnsApiClient(integration(), client)

        when:
        boolean exists = apiClient.doesRecordExist(record())

        then:
        1 * client.callJsonApi(*_) >> new ServiceResponse(false, 'connection refused', null, null)
        !exists
    }

    @Unroll
    def "doesRecordExist matches record sets regardless of trailing dot (record fqdn='#recordFqdn', api name='#apiName')"() {
        given:
        PowerDnsApiClient apiClient = new PowerDnsApiClient(integration(), client)

        when:
        boolean exists = apiClient.doesRecordExist(record(fqdn: recordFqdn))

        then:
        1 * client.callJsonApi(*_) >> new ServiceResponse(true, null, null, [rrsets: [[name: apiName, type: 'A']]])
        exists == expected

        where:
        recordFqdn           | apiName               | expected
        'www.example.com.'   | 'www.example.com.'    | true
        'www.example.com'    | 'www.example.com.'    | true
        'www.example.com.'   | 'www.example.com'     | true
        'other.example.com.' | 'www.example.com.'    | false
    }

    def "getRecordSetResults reads 'records' for service version 3 and 'rrsets' otherwise"() {
        given:
        PowerDnsApiClient apiClient = new PowerDnsApiClient(integration(serviceVersion), client)

        expect:
        apiClient.getRecordSetResults([results: data]) == expected

        where:
        serviceVersion | data                          | expected
        '3'            | [records: [[name: 'a']]]      | [[name: 'a']]
        '4'            | [rrsets: [[name: 'b']]]       | [[name: 'b']]
    }

    def "getRecordContent joins record content for version 4+ and reads content directly for version 3"() {
        expect:
        new PowerDnsApiClient(integration(serviceVersion), client).getRecordContent(data) == expected

        where:
        serviceVersion | data                                                        | expected
        '3'            | [content: '10.0.0.1']                                      | '10.0.0.1'
        '4'            | [records: [[content: '10.0.0.1'], [content: '10.0.0.2']]]  | '10.0.0.1\n10.0.0.2'
    }

    def "getRecordCreateBody uses PowerDNS v3 payload shape when serviceVersion is '3'"() {
        given:
        PowerDnsApiClient apiClient = new PowerDnsApiClient(integration('3'), client)

        when:
        Map body = apiClient.getRecordCreateBody('www.example.com.', 'A', '10.0.0.1', 300, true)

        then:
        body.rrsets[0].records[0]['set-ptr'] == true
        body.rrsets[0].records[0].name == 'www.example.com'
    }

    def "getRecordCreateBody uses PowerDNS v4+ payload shape when serviceVersion is not '3'"() {
        given:
        PowerDnsApiClient apiClient = new PowerDnsApiClient(integration('4'), client)

        when:
        Map body = apiClient.getRecordCreateBody('www.example.com.', 'A', '10.0.0.1', 300, true)

        then:
        !body.rrsets[0].records[0].containsKey('set-ptr')
        body.rrsets[0].name == 'www.example.com.'
    }
}
