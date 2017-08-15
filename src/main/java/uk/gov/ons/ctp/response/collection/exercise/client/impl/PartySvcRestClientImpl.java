package uk.gov.ons.ctp.response.collection.exercise.client.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import uk.gov.ons.ctp.common.rest.RestClient;
import uk.gov.ons.ctp.response.collection.exercise.client.PartySvcClient;
import uk.gov.ons.ctp.response.collection.exercise.config.AppConfig;
import uk.gov.ons.ctp.response.party.representation.Party;
import uk.gov.ons.ctp.response.sample.representation.SampleUnitDTO;

/**
 * HTTP RestClient implementation for calls to the Party service
 *
 */
@Component
public class PartySvcRestClientImpl implements PartySvcClient {

  @Autowired
  private AppConfig appConfig;

  @Autowired
  @Qualifier("partySvc")
  private RestClient partySvcClientRestTemplate;

  @Override
  public Party requestParty(SampleUnitDTO.SampleUnitType sampleUnitType, String sampleUnitRef)
      throws RestClientException {
    return partySvcClientRestTemplate.getResource(appConfig.getPartySvc().getRequestPartyPath(), Party.class,
        sampleUnitType, sampleUnitRef);
  }
}
