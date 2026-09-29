/*
* Copyright 2022 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.morpheusdata.powerdns

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkDomainRecord
import groovy.util.logging.Slf4j

/**
 * Owns the {@link HttpApiClient} used to talk to a Power DNS server and encapsulates the raw API request/response
 * handling (URL/path building, auth headers, request body formats, response parsing) for a single
 * {@link AccountIntegration}. This keeps {@link PowerDnsProvider} focused on the Morpheus DNSProvider contract and
 * sync orchestration, separate from the mechanics of calling the Power DNS API.
 *
 * @author Jordan Sutton
 */
@Slf4j
class PowerDnsApiClient {

    private final AccountIntegration integration
    private final HttpApiClient client

    PowerDnsApiClient(AccountIntegration integration) {
        this.integration = integration
        this.client = new HttpApiClient()
    }

    /**
     * Releases the underlying {@link HttpApiClient} resources. Must be called once this client is no longer needed.
     */
    void shutdown() {
        client.shutdownClient()
    }

    /**
     * Lists the zones configured on the Power DNS server for this integration.
     *
     * @return a Map with a {@code success} flag and, when successful, the raw {@code results} list of zones.
     */
    Map listZones() {
        def rtn = [success:false, errors: [:]]
        def apiPath = getApiPath('/servers/localhost/zones')
        def results = client.callJsonApi(serviceUrl(), apiPath, null, null, requestOptions(), 'GET')
        rtn.success = results?.success && !results?.error
        log.debug("listZones results: ${results}")
        if(rtn.success) {
            rtn.results = results.data
            rtn.headers = results.headers
        } else {
            rtn.msg = results.error
        }
        return rtn
    }

    /**
     * Lists the record sets currently on the Power DNS zone associated with the passed {@link NetworkDomain}.
     *
     * @param domain the zone whose records should be listed.
     * @param opts optional {@code max}/{@code phrase} query params.
     * @return a Map with a {@code success} flag and, when successful, the raw {@code results} payload.
     */
    Map listRecords(NetworkDomain domain, Map opts = [:]) {
        def rtn = [success:false]
        if(domain?.externalId) {
            def apiPath = cleanApiPath('/' + domain.externalId)
            Map<String,String> query = [:]
            if(opts.max)
                query.max = opts.max.toString()
            if(opts.phrase)
                query.q = opts.phrase.toString()

            def results = client.callJsonApi(serviceUrl(), apiPath, null, null, queryRequestOptions(query), 'GET')

            rtn.success = results?.success && results?.error != true
            log.debug("listRecords results: ${results}")
            if(rtn.success) {
                rtn.results = results.data
                rtn.headers = results.headers
            }
        }
        return rtn
    }

    /**
     * Sends the Power DNS API call to create/patch the record described by the passed {@link NetworkDomainRecord}.
     *
     * @param record The domain record to create on the Power DNS zone.
     * @param createPtr whether Power DNS should also create a PTR record for this entry.
     * @return the raw {@link HttpApiClient} response from the Power DNS create/patch call.
     */
    def createRecord(NetworkDomainRecord record, Boolean createPtr) {
        String fqdn = normalizeFqdn(record.fqdn)
        def apiPath = cleanApiPath('/' + record.networkDomain.externalId)
        def body = getRecordCreateBody(fqdn, record.type, record.content, record.ttl ?: 86400, createPtr)

        return client.callJsonApi(serviceUrl(), apiPath, null, null, requestOptions(body), 'PATCH')
    }

    /**
     * Sends the Power DNS API call to delete the record described by the passed {@link NetworkDomainRecord}.
     *
     * @param record The domain record to delete from the Power DNS zone.
     * @return the raw {@link HttpApiClient} response from the Power DNS delete call.
     */
    def deleteRecord(NetworkDomainRecord record) {
        String fqdn = normalizeFqdn(record.fqdn)
        def apiPath = cleanApiPath('/' + record.networkDomain.externalId)
        def body = getRecordDeleteBody(fqdn, record.type, record.content)

        return client.callJsonApi(serviceUrl(), apiPath, null, null, requestOptions(body), 'PATCH')
    }

    /**
     * Determines whether a record with the same name and type as the passed {@link NetworkDomainRecord} already
     * exists on the live Power DNS zone. Queried directly against Power DNS (rather than the Morpheus sync cache)
     * since the cache is only refreshed on the periodic sync pass and would otherwise miss records created moments
     * earlier, allowing duplicates to slip through in the window before the next sync.
     *
     * @param record The record data to look up.
     * @return {@code true} if a record with the same name and type is found on the Power DNS zone, {@code false} otherwise.
     */
    boolean doesRecordExist(NetworkDomainRecord record) {
        def listResults = listRecords(record.networkDomain)
        if(!listResults.success) {
            log.warn("doesRecordExist: unable to retrieve existing records for domain ${record.networkDomain?.name} from Power DNS; proceeding without a duplicate check.")
            return false
        }

        List<Map> apiItems = getRecordSetResults(listResults) as List<Map>
        return apiItems?.any { Map apiItem -> matchesRecord(apiItem, record) } ?: false
    }

    /**
     * Compares a Power DNS API record entry (as returned by the zone GET call) against a Morpheus
     * {@link NetworkDomainRecord} to determine if they refer to the same record.
     *
     * @param apiItem a single record entry as returned by the Power DNS zone API.
     * @param record The Morpheus record to compare against.
     * @return {@code true} if the name and type match, {@code false} otherwise.
     */
    private boolean matchesRecord(Map apiItem, NetworkDomainRecord record) {
        String recordType = record.type?.toUpperCase()
        String fqdn = normalizeFqdn(record.fqdn)
        String apiName = normalizeFqdn(apiItem.name?.toString())

        return apiItem.type?.toString()?.toUpperCase() == recordType && apiName == fqdn
    }

    /**
     * Ensures the passed fqdn ends with a trailing dot, matching the format used by Power DNS record names.
     *
     * @param fqdn the fqdn to normalize.
     * @return the fqdn with a trailing dot, or the original value if null/blank.
     */
    private String normalizeFqdn(String fqdn) {
        if(fqdn && !fqdn.endsWith('.')) {
            return fqdn + '.'
        }
        return fqdn
    }

    /**
     * Extracts the record set list from a Power DNS API response, accounting for the differing response shape
     * between Power DNS API versions 3 and 4+.
     *
     * @param data the raw response Map from {@link #listRecords}.
     * @return the list of record sets, or null if not present.
     */
    List<Map> getRecordSetResults(Map data) {
        def rtn
        if(integration.serviceVersion == '3') {
            rtn = data?.results?.records
        } else {
            rtn = data?.results?.rrsets
        }
        return rtn as List<Map>
    }

    /**
     * Builds the Power DNS request body used to create/patch a record, accounting for the differing payload
     * shape between Power DNS API versions 3 and 4+.
     */
    Map getRecordCreateBody(String fqdn, String recordType, String content, Integer ttl = 86400, Boolean createPtr = false) {
        def rtn
        if(integration.serviceVersion == '3') {
            def recordName = NetworkUtility.getFriendlyDomainName(fqdn)
            rtn = [
                    rrsets: [
                            [name:recordName, type:recordType, ttl:ttl, changetype:'REPLACE',
                             records:[
                                     [content:content, disabled:false, name:recordName, ttl:ttl, type:recordType, 'set-ptr':createPtr]
                             ]
                            ]
                    ]
            ]
        } else {
            def recordName = fqdn
            rtn = [
                    rrsets: [
                            [name:recordName, type:recordType, ttl:ttl, changetype:'REPLACE',
                             records:[
                                     [content:content, disabled:false]
                             ]
                            ]
                    ]
            ]
        }
        return rtn
    }

    /**
     * Builds the Power DNS request body used to delete a record, accounting for the differing payload shape
     * between Power DNS API versions 3 and 4+.
     */
    Map getRecordDeleteBody(String fqdn, String recordType, String content, Integer ttl = 86400) {
        def rtn
        if(integration.serviceVersion == '3') {
            def recordName = NetworkUtility.getFriendlyDomainName(fqdn)
            rtn = [
                    rrsets: [
                            [name:recordName, type:recordType, ttl:ttl, changetype:'DELETE',
                             records:[
                                     [content:content, disabled:false, name:recordName, ttl:ttl, type:recordType]
                             ]
                            ]
                    ]
            ]
        } else {
            def recordName = fqdn
            rtn = [
                    rrsets: [
                            [name:recordName, type:recordType, ttl:ttl, changetype:'DELETE',
                             records:[
                                     [content:content, disabled:false]
                             ]
                            ]
                    ]
            ]
        }
        return rtn
    }

    /**
     * Extracts the human-readable content for a record set entry, accounting for the differing response shape
     * between Power DNS API versions 3 and 4+.
     */
    String getRecordContent(Map data) {
        String rtn
        if(integration.serviceVersion == '3') {
            rtn = data.content
        } else {
            rtn = data.records?.collect{it.content}?.join('\n')
        }
        return rtn
    }

    /**
     * Returns the normalized Power DNS base service URL used for API calls, useful for connectivity checks.
     */
    String getServiceUrl() {
        return serviceUrl()
    }

    private HttpApiClient.RequestOptions requestOptions(Map body = null) {
        return new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json','X-API-KEY':token()], ignoreSSL: true, body:body)
    }

    private HttpApiClient.RequestOptions queryRequestOptions(Map<String,String> queryParams) {
        return new HttpApiClient.RequestOptions(headers:['Content-Type':'application/json','X-API-KEY':token()], ignoreSSL: true, queryParams: queryParams)
    }

    private String token() {
        return integration.credentialData?.password ?: integration.servicePassword
    }

    private String serviceUrl() {
        String rtn = integration.serviceUrl
        def slashIndex = rtn.indexOf('/', 10)
        if(slashIndex > 10)
            rtn = rtn.substring(0, slashIndex)
        return rtn
    }

    private String cleanApiPath(String path) {
        String rtn = path
        if(rtn?.startsWith('//'))
            rtn = rtn.substring(1)
        return rtn
    }

    private String getApiPath(String path) {
        if(integration.serviceVersion == '3')
            return path
        return '/api/v1' + path
    }
}
