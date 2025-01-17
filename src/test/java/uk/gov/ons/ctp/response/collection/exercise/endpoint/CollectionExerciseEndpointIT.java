package uk.gov.ons.ctp.response.collection.exercise.endpoint;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.thoughtworks.xstream.XStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.rules.SpringClassRule;
import org.springframework.test.context.junit4.rules.SpringMethodRule;
import uk.gov.ons.ctp.response.collection.exercise.config.AppConfig;
import uk.gov.ons.ctp.response.collection.exercise.domain.CollectionExercise;
import uk.gov.ons.ctp.response.collection.exercise.lib.casesvc.message.sampleunitnotification.SampleUnitParent;
import uk.gov.ons.ctp.response.collection.exercise.lib.common.error.CTPException;
import uk.gov.ons.ctp.response.collection.exercise.lib.rabbit.Rabbitmq;
import uk.gov.ons.ctp.response.collection.exercise.lib.rabbit.SimpleMessageBase;
import uk.gov.ons.ctp.response.collection.exercise.lib.rabbit.SimpleMessageListener;
import uk.gov.ons.ctp.response.collection.exercise.lib.rabbit.SimpleMessageSender;
import uk.gov.ons.ctp.response.collection.exercise.lib.sample.representation.SampleSummaryDTO;
import uk.gov.ons.ctp.response.collection.exercise.lib.sampleunit.definition.SampleUnit;
import uk.gov.ons.ctp.response.collection.exercise.repository.CollectionExerciseRepository;
import uk.gov.ons.ctp.response.collection.exercise.repository.EventRepository;
import uk.gov.ons.ctp.response.collection.exercise.repository.SampleLinkRepository;
import uk.gov.ons.ctp.response.collection.exercise.repository.SampleUnitGroupRepository;
import uk.gov.ons.ctp.response.collection.exercise.repository.SampleUnitRepository;
import uk.gov.ons.ctp.response.collection.exercise.representation.CollectionExerciseDTO;
import uk.gov.ons.ctp.response.collection.exercise.representation.EventDTO;
import uk.gov.ons.ctp.response.collection.exercise.representation.ResponseEventDTO;
import uk.gov.ons.ctp.response.collection.exercise.service.CollectionTransitionEvent;
import uk.gov.ons.ctp.response.collection.exercise.service.EventService;
import uk.gov.ons.ctp.response.collection.exercise.validation.ValidateSampleUnits;

/** A class to contain integration tests for the collection exercise service */
@ContextConfiguration
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class CollectionExerciseEndpointIT {
  private static final Logger log = LoggerFactory.getLogger(CollectionExerciseEndpointIT.class);

  private static final UUID TEST_SURVEY_ID =
      UUID.fromString("c23bb1c1-5202-43bb-8357-7a07c844308f");
  private static final String TEST_USERNAME = "admin";
  private static final String TEST_PASSWORD = "secret";

  @LocalServerPort private int port;

  @Autowired private CollectionExerciseRepository collexRepository;

  @Autowired private ObjectMapper mapper;

  @Autowired private AppConfig appConfig;

  @Autowired private CollectionExerciseRepository collectionExerciseRepository;

  @Autowired private SampleUnitRepository sampleUnitRepository;

  @Autowired private SampleUnitGroupRepository sampleUnitGroupRepository;

  @Autowired private SampleLinkRepository sampleLinkRepository;

  @Autowired private EventRepository eventRepository;

  @ClassRule public static final SpringClassRule SPRING_CLASS_RULE = new SpringClassRule();

  @Rule public final SpringMethodRule springMethodRule = new SpringMethodRule();

  @ClassRule
  public static WireMockClassRule wireMockRule =
      new WireMockClassRule(options().port(18002).bindAddress("localhost"));

  private CollectionExerciseClient client;

  /** Method to set up integration test */
  @Before
  public void setUp() {
    wireMockRule.resetAll();

    sampleUnitRepository.deleteAllInBatch();
    sampleLinkRepository.deleteAllInBatch();
    eventRepository.deleteAllInBatch();
    sampleUnitGroupRepository.deleteAllInBatch();
    collectionExerciseRepository.deleteAllInBatch();

    client = new CollectionExerciseClient(this.port, TEST_USERNAME, TEST_PASSWORD, this.mapper);
  }

  /**
   * Method to test construction of a collection exercise via the API - Create a collection exercise
   * - Get the collection exercise from the returned Location header - Assert the collection
   * exercise fields match those expected
   *
   * @throws CTPException thrown by error creating collection exercise
   */
  @Test
  public void shouldCreateCollectionExercise() throws Exception {
    stubSurveyServiceBusiness();
    String exerciseRef = "899990";
    String userDescription = "Test Description";
    Pair<Integer, String> result =
        this.client.createCollectionExercise(TEST_SURVEY_ID, exerciseRef, userDescription);

    assertEquals(201, (int) result.getLeft());

    CollectionExerciseDTO newCollex = this.client.getCollectionExercise(result.getRight());

    assertEquals(TEST_SURVEY_ID, UUID.fromString(newCollex.getSurveyId()));
    assertEquals(exerciseRef, newCollex.getExerciseRef());
    assertEquals(userDescription, newCollex.getUserDescription());
  }

  @Test
  public void shouldUpdateEventDate() throws IOException, CTPException, InterruptedException {
    stubCollectionInstrumentCount();
    stubSurveyServiceBusiness();
    String exerciseRef = "899990";
    String userDescription = "Test Description";

    final Pair<Integer, String> response =
        client.createCollectionExercise(TEST_SURVEY_ID, exerciseRef, userDescription);
    final CollectionExerciseDTO collectionExercise =
        client.getCollectionExercise(response.getRight());

    final EventDTO mps = createEventDTO(collectionExercise, EventService.Tag.mps, 2);
    createEventDTO(collectionExercise, EventService.Tag.go_live, 3);
    createEventDTO(collectionExercise, EventService.Tag.return_by, 4);
    createEventDTO(collectionExercise, EventService.Tag.exercise_end, 5);

    Date newDate = Date.from(Instant.now().plus(1, ChronoUnit.DAYS));

    mps.setTimestamp(newDate);
    HttpResponse res = client.updateEvent(mps);
    ResponseEventDTO responseEventDTO = (ResponseEventDTO) res.getBody();

    final List<EventDTO> events =
        client
            .getEvents(collectionExercise.getId())
            .stream()
            .filter(eventDTO -> EventService.Tag.mps.hasName(eventDTO.getTag()))
            .collect(Collectors.toList());

    final EventDTO createdEvent = events.get(0);
    assertThat(createdEvent.getTag(), is(EventService.Tag.mps.name()));
    assertThat(
        DateUtils.round(createdEvent.getTimestamp(), Calendar.MINUTE).getTime(),
        is(DateUtils.round(newDate, Calendar.MINUTE).getTime()));
    assertTrue(responseEventDTO.getInfo() == null);
  }

  @Test
  public void shouldTransitionCollectionExerciseToReadyToReviewOnSampleSummaryLink()
      throws Exception {
    // Given
    stubSurveyServiceBusiness();
    stubCollectionInstrumentCount();
    stubGetPartyBySampleUnitRef();
    SampleSummaryDTO sampleSummary = stubSampleSummary();
    UUID collectionExerciseId = createScheduledCollectionExercise();

    // When
    BlockingQueue<String> queue =
        getMessageListener()
            .listen(
                SimpleMessageBase.ExchangeType.Direct,
                "collex-transition-exchange",
                "Collex.Transition.binding");
    this.client.linkSampleSummary(collectionExerciseId, sampleSummary.getId());

    // Then
    CollectionTransitionEvent collectionTransitionEvent = pollForReadyToReview(queue);

    assertEquals(collectionExerciseId, collectionTransitionEvent.getCollectionExerciseId());
    assertEquals(
        CollectionExerciseDTO.CollectionExerciseState.READY_FOR_REVIEW,
        collectionTransitionEvent.getState());

    CollectionExerciseDTO newCollex = this.client.getCollectionExercise(collectionExerciseId);
    assertEquals(
        CollectionExerciseDTO.CollectionExerciseState.READY_FOR_REVIEW, newCollex.getState());
  }

  @Test
  public void shouldTransitionCollectionExerciseToReadyToReviewOnSampleSummaryActive()
      throws Exception {
    // Given;
    stubSurveyServiceBusiness();
    stubCollectionInstrumentCount();
    stubGetPartyBySampleUnitRef();
    SampleSummaryDTO sampleSummary = stubSampleSummaryInitThenActive();
    UUID collectionExerciseId = createScheduledCollectionExercise();

    this.client.linkSampleSummary(collectionExerciseId, sampleSummary.getId());
    BlockingQueue<String> queue =
        getMessageListener()
            .listen(
                SimpleMessageBase.ExchangeType.Direct,
                "collex-transition-exchange",
                "Collex.Transition.binding");

    // This will cause an exception to be thrown as there is no collection instrument service
    // this is harmless to our purpose
    // When
    getMessageSender()
        .sendMessage(
            "sample-outbound-exchange",
            "Sample.SampleUploadFinished.binding",
            this.mapper.writeValueAsString(sampleSummary));

    // Then
    CollectionTransitionEvent collectionTransitionEvent = pollForReadyToReview(queue);

    assertEquals(collectionExerciseId, collectionTransitionEvent.getCollectionExerciseId());
    assertEquals(
        CollectionExerciseDTO.CollectionExerciseState.READY_FOR_REVIEW,
        collectionTransitionEvent.getState());

    CollectionExerciseDTO newCollex = this.client.getCollectionExercise(collectionExerciseId);
    assertEquals(
        CollectionExerciseDTO.CollectionExerciseState.READY_FOR_REVIEW, newCollex.getState());
  }

  @Test
  public void ensureSampleUnitIdIsPropagatedHereBusiness() throws Exception {
    stubSurveyServiceBusiness();
    stubGetPartyNoAssociations();
    stubCollectionInstrumentCount();

    SampleUnitParent sampleUnit = ensureSampleUnitIdIsPropagatedHere("B");

    assertNotNull("Party id must be not null", sampleUnit.getPartyId());
  }

  @Test
  public void ensureSampleUnitIdIsPropagatedHereBusinessWithExistingEnrolments() throws Exception {
    stubSurveyServiceBusiness();
    stubGetPartyWithAssociations();
    stubCollectionInstrumentCount();
    SampleUnitParent sampleUnit = ensureSampleUnitIdIsPropagatedHere("B");

    assertNotNull("Party id must be not null", sampleUnit.getPartyId());
  }

  private SampleUnitParent ensureSampleUnitIdIsPropagatedHere(String type) throws Exception {
    createCollectionInstrumentStub();

    SampleUnit sampleUnit = new SampleUnit();
    UUID id = UUID.randomUUID();
    SimpleMessageSender sender = getMessageSender();

    CollectionExerciseDTO collex = createCollectionExercise(getRandomRef());

    sampleUnit.setId(id.toString());
    sampleUnit.setSampleUnitRef("LMS0001");
    sampleUnit.setCollectionExerciseId(collex.getId().toString());
    sampleUnit.setSampleUnitType(type);

    if (type.equalsIgnoreCase("B") || type.equalsIgnoreCase("BI")) {
      sampleUnit.setFormType("");
    }

    // sampleUnit.setSampleAttributes(new SampleUnit.SampleAttributes(new ArrayList<>()));

    setSampleSize(collex, 1);
    setState(collex, CollectionExerciseDTO.CollectionExerciseState.EXECUTION_STARTED);

    SimpleMessageListener listener = getMessageListener();
    BlockingQueue<String> queue =
        listener.listen(
            SimpleMessageBase.ExchangeType.Direct,
            "collection-outbound-exchange",
            "Case.CaseDelivery.binding");

    String xml = sampleUnitToXmlString(sampleUnit);

    log.info("xml = " + xml);

    sender.sendMessage("sample-outbound-exchange", "Sample.SampleDelivery.binding", xml);

    //// When PlanScheduler and ActionDistributor runs
    final int threadPort = this.port;
    Thread thread =
        new Thread(
            () -> {
              //// When PlanScheduler and ActionDistributor runs
              try {
                for (int i = 0; i < 3; i++) {
                  HttpResponse<String> distributeResponse =
                      Unirest.get("http://localhost:" + threadPort + "/cron/sample-unit-validation")
                          .basicAuth("admin", "secret")
                          .header("accept", "application/json")
                          .asString();
                  assertThat(distributeResponse.getStatus(), Matchers.is(200));
                  assertThat(
                      distributeResponse.getBody(),
                      Matchers.is("Completed sample unit validation"));

                  HttpResponse<String> response =
                      Unirest.get(
                              "http://localhost:" + threadPort + "/cron/sample-unit-distribution")
                          .basicAuth("admin", "secret")
                          .header("accept", "application/json")
                          .asString();
                  assertThat(response.getStatus(), Matchers.is(200));
                  assertThat(response.getBody(), Matchers.is("Completed sample unit distribution"));
                }
              } catch (Exception e) {
                log.error("exception in thread", e);
              }
            });
    thread.start();
    // The service stubs will exit once the call below times out preventing further debugging of
    // this test in other
    // threads (i.e. you have 2 minutes to debug before the service calls will start to fail)
    String message = queue.poll(2, TimeUnit.MINUTES);
    // If you need more than 2 minutes to debug this test, then either change the timeout above or
    // comment that line
    // and uncomment the one below (which gives infinite time).
    // String message = queue.take();
    log.info("message = " + message);
    assertNotNull("Timeout waiting for message to arrive in Case.CaseDelivery", message);

    JAXBContext jaxbContext = JAXBContext.newInstance(SampleUnitParent.class);
    SampleUnitParent sampleUnitParent =
        (SampleUnitParent)
            jaxbContext
                .createUnmarshaller()
                .unmarshal(new ByteArrayInputStream(message.getBytes()));

    assertEquals(id, UUID.fromString(sampleUnitParent.getId()));

    return sampleUnitParent;
  }

  private EventDTO createEventDTO(
      final CollectionExerciseDTO collectionExercise,
      final EventService.Tag tag,
      final Integer daysInFuture) {
    final EventDTO event = new EventDTO();
    event.setTimestamp(Date.from(Instant.now().plus(daysInFuture, ChronoUnit.DAYS)));
    event.setTag(tag.toString());
    event.setCollectionExerciseId(collectionExercise.getId());

    client.createCollectionExerciseEvent(event);
    return event;
  }

  /**
   * Creates a new SimpleMessageSender based on the config in AppConfig
   *
   * @return a new SimpleMessageSender
   */
  private SimpleMessageSender getMessageSender() {
    Rabbitmq config = this.appConfig.getRabbitmq();

    return new SimpleMessageSender(
        config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
  }

  /**
   * Creates a new SimpleMessageListener based on the config in AppConfig
   *
   * @return a new SimpleMessageListener
   */
  private SimpleMessageListener getMessageListener() {
    Rabbitmq config = this.appConfig.getRabbitmq();

    return new SimpleMessageListener(
        config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
  }

  private String sampleUnitToXmlString(SampleUnit sampleUnit) throws JAXBException {
    JAXBContext jaxbContext = JAXBContext.newInstance(SampleUnit.class);
    StringWriter stringWriter = new StringWriter();
    jaxbContext.createMarshaller().marshal(sampleUnit, stringWriter);
    return stringWriter.toString();
  }

  private CollectionExerciseDTO createCollectionExercise(String exerciseRef) throws CTPException {
    Pair<Integer, String> result =
        this.client.createCollectionExercise(
            CollectionExerciseEndpointIT.TEST_SURVEY_ID, exerciseRef, "Test description");

    assertEquals(201, (int) result.getLeft());

    return this.client.getCollectionExercise(result.getRight());
  }

  private void setSampleSize(CollectionExerciseDTO collex, int sampleSize) {
    CollectionExercise c = collexRepository.findOneById(collex.getId());
    c.setSampleSize(sampleSize);
    collexRepository.saveAndFlush(c);
  }

  private String getRandomRef() {
    Random r = new Random();
    return String.valueOf(r.nextInt(1_000_000));
  }

  private void setState(
      CollectionExerciseDTO collex, CollectionExerciseDTO.CollectionExerciseState state) {
    CollectionExercise c = collexRepository.findOneById(collex.getId());
    c.setState(state);
    collexRepository.saveAndFlush(c);
  }

  private String loadResourceAsString(Class clazz, String resourceName) throws IOException {
    InputStream is = clazz.getResourceAsStream(resourceName);
    StringWriter writer = new StringWriter();
    IOUtils.copy(is, writer, StandardCharsets.UTF_8.name());
    return writer.toString();
  }

  private void createCollectionInstrumentStub() throws IOException {
    String json =
        loadResourceAsString(
            ValidateSampleUnits.class, "ValidateSampleUnitsTest.CollectionInstrumentDTO.json");
    this.wireMockRule.stubFor(
        get(urlPathEqualTo("/collection-instrument-api/1.0.2/collectioninstrument"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
  }

  private SampleSummaryDTO stubSampleSummary() throws IOException {
    SampleSummaryDTO sampleSummary = new SampleSummaryDTO();
    sampleSummary.setState(SampleSummaryDTO.SampleState.ACTIVE);
    sampleSummary.setId(UUID.randomUUID());
    this.wireMockRule.stubFor(
        get(urlPathEqualTo("/samples/samplesummary/" + sampleSummary.getId()))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(sampleSummary))));
    return sampleSummary;
  }

  private SampleSummaryDTO stubSampleSummaryInitThenActive() throws IOException {
    SampleSummaryDTO sampleSummary = new SampleSummaryDTO();
    sampleSummary.setState(SampleSummaryDTO.SampleState.INIT);
    sampleSummary.setId(UUID.randomUUID());
    this.wireMockRule.stubFor(
        get(urlPathEqualTo("/samples/samplesummary/" + sampleSummary.getId()))
            .inScenario("INIT then ACTIVE")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("ACTIVE")
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(sampleSummary))));
    sampleSummary.setState(SampleSummaryDTO.SampleState.ACTIVE);
    this.wireMockRule.stubFor(
        get(urlPathEqualTo("/samples/samplesummary/" + sampleSummary.getId()))
            .inScenario("INIT then ACTIVE")
            .whenScenarioStateIs("ACTIVE")
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(sampleSummary))));
    return sampleSummary;
  }

  private void stubCollectionInstrumentCount() throws IOException {
    this.wireMockRule.stubFor(
        get(urlPathEqualTo("/collection-instrument-api/1.0.2/collectioninstrument/count"))
            .willReturn(aResponse().withBody("1")));
  }

  private void stubGetPartyBySampleUnitRef() throws IOException {
    String json =
        loadResourceAsString(
            CollectionExerciseEndpointIT.class,
            "CollectionExerciseEndpointIT.PartyDTO.with-associations.json");
    this.wireMockRule.stubFor(
        get(urlPathMatching("/party-api/v1/parties/type/(.*)/ref/(.*)"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
  }

  private void stubGetPartyNoAssociations() throws IOException {
    String json =
        loadResourceAsString(
            CollectionExerciseEndpointIT.class,
            "CollectionExerciseEndpointIT.PartyDTO.no-associations.json");
    this.wireMockRule.stubFor(
        get(urlPathMatching("/party-api/v1/parties/type/B/ref/(.*)"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
  }

  private void stubGetPartyWithAssociations() throws IOException {
    String json =
        loadResourceAsString(
            CollectionExerciseEndpointIT.class,
            "CollectionExerciseEndpointIT.PartyDTO.with-associations.json");
    this.wireMockRule.stubFor(
        get(urlPathMatching("/party-api/v1/parties/type/B/ref/(.*)"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
  }

  private void createSurveyServiceClassifierStubs() throws IOException {
    String json =
        loadResourceAsString(
            CollectionExerciseEndpointIT.class,
            "CollectionExerciseEndpointIT.SurveyClassifierDTO.json");
    this.wireMockRule.stubFor(
        get(urlPathMatching(
                "/surveys/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
                    + "/classifiertypeselectors"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
    json =
        loadResourceAsString(
            CollectionExerciseEndpointIT.class,
            "CollectionExerciseEndpointIT.SurveyClassifierTypeDTO.json");
    this.wireMockRule.stubFor(
        get(urlPathMatching(
                "/surveys/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
                    + "/classifiertypeselectors/"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
  }

  private void stubGetSurvey() throws IOException {
    createSurveyServiceClassifierStubs();

    String json =
        loadResourceAsString(
            CollectionExerciseEndpointIT.class,
            "CollectionExerciseEndpointIT.SurveyDTO.social.json");
    this.wireMockRule.stubFor(
        get(urlPathMatching(
                "/surveys/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
  }

  private void stubSurveyServiceBusiness() throws IOException {
    createSurveyServiceClassifierStubs();

    String json =
        loadResourceAsString(
            CollectionExerciseEndpointIT.class,
            "CollectionExerciseEndpointIT.SurveyDTO.business.json");
    this.wireMockRule.stubFor(
        get(urlPathMatching(
                "/surveys/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
  }

  private UUID createScheduledCollectionExercise() throws CTPException, IOException {
    String exerciseRef = getRandomRef();
    String userDescription = "Test Description";
    Pair<Integer, String> result =
        client.createCollectionExercise(TEST_SURVEY_ID, exerciseRef, userDescription);
    String collexId = StringUtils.substringAfterLast(result.getRight(), "/");
    UUID collectionExerciseId = UUID.fromString(collexId);

    Arrays.stream(EventService.Tag.values())
        .filter(EventService.Tag::isMandatory)
        .forEach(
            t -> {
              EventDTO event = new EventDTO();
              event.setCollectionExerciseId(collectionExerciseId);
              event.setTag(t.name());
              event.setTimestamp(new Date());
              client.createCollectionExerciseEvent(event);
            });

    return collectionExerciseId;
  }

  private CollectionTransitionEvent pollForReadyToReview(BlockingQueue<String> queue)
      throws InterruptedException {
    String collexTransition = queue.take();
    CollectionTransitionEvent collectionTransitionEvent =
        (CollectionTransitionEvent) new XStream().fromXML(collexTransition);
    return collectionTransitionEvent
            .getState()
            .equals(CollectionExerciseDTO.CollectionExerciseState.READY_FOR_REVIEW)
        ? collectionTransitionEvent
        : pollForReadyToReview(queue);
  }
}
