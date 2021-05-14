package org.icann.rdapconformance.validator.workflow.profile.rdap_response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.icann.rdapconformance.validator.configuration.RDAPValidatorConfiguration;
import org.icann.rdapconformance.validator.workflow.profile.ProfileJsonValidationTestBase;
import org.icann.rdapconformance.validator.workflow.rdap.RDAPQueryType;
import org.icann.rdapconformance.validator.workflow.rdap.RDAPValidatorResults;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class QueryValidationTest<T extends QueryValidation> extends
    ProfileJsonValidationTestBase {

  private final RDAPQueryType baseQueryType;
  private final Class<T> validationClass;
  private final String baseUri;
  protected RDAPQueryType queryType;
  private RDAPValidatorConfiguration config;

  public QueryValidationTest(String validJsonResourcePath, String testGroupName,
      RDAPQueryType baseQueryType,
      Class<T> validationClass) {
    super(validJsonResourcePath, testGroupName);
    this.baseQueryType = baseQueryType;
    this.validationClass = validationClass;
    this.baseUri = "http://" + this.baseQueryType.name();
  }

  @BeforeMethod
  public void setUp() throws IOException {
    super.setUp();
    this.queryType = baseQueryType;
    config = mock(RDAPValidatorConfiguration.class);
    doReturn(URI.create(baseUri + "/test.example")).when(config).getUri();
  }

  @Override
  public QueryValidation getProfileValidation() {
    try {
      return validationClass.getConstructor(String.class, RDAPValidatorResults.class,
          RDAPValidatorConfiguration.class, RDAPQueryType.class)
          .newInstance(jsonObject.toString(), results, config, queryType);
    } catch (Exception e) {
      return null;
    }
  }

  @Test
  public void testValidate_UriContainsOnlyALabelOrNrLdhName_Ok() {
    replaceValue("ldhName", "toto");
    doReturn(URI.create(baseUri + "/test.xn--viagnie-eya.example")).when(config).getUri();
    validate();
  }

  @Test
  public void testValidate_URIContainsULabel_Ok() {
    doReturn(URI.create(baseUri + "/test.viagénie.example")).when(config).getUri();
    jsonObject.put("unicodeName", "test.viagénie.example");
    validate();
  }

  @Test
  public void testValidate_UriContainsOnlyALabelButNoLdhName_AddResults46100() {
    doReturn(URI.create(baseUri + "/test.xn--viagnie-eya.example")).when(config).getUri();
    removeKey("ldhName");
    QueryValidation validation = getProfileValidation();
    validate(validation.code, jsonObject.toString(),
        String.format("The RDAP Query URI contains only A-label or NR-LDH labels, the topmost %s "
                + "object does not contain a ldhName member. "
                + "See section %s of the RDAP_Response_Profile_2_1.", baseQueryType,
            validation.sectionName));
  }

  @Test
  public void testValidate_UriContainsULabelButNoUnicodeName_AddResults46101() {
    doReturn(URI.create(baseUri + "/test.viagénie.example")).when(config).getUri();
    QueryValidation validation = getProfileValidation();
    validate(validation.code - 1, jsonObject.toString(),
        String.format("The RDAP Query URI contains one or more U-label, the topmost %s object does "
                + "not contain a unicodeName member. "
                + "See section %s of the RDAP_Response_Profile_2_1.",
            baseQueryType, validation.sectionName));
  }

  @Test
  public void testDoLaunch() {
    for (RDAPQueryType queryTypeBeingTested : List
        .of(RDAPQueryType.HELP, RDAPQueryType.NAMESERVERS, RDAPQueryType.NAMESERVER,
            RDAPQueryType.ENTITY, RDAPQueryType.DOMAIN)) {
      queryType = queryTypeBeingTested;
      if (queryType.equals(baseQueryType)) {
        assertThat(getProfileValidation().doLaunch()).isTrue();
      } else {
        assertThat(getProfileValidation().doLaunch()).isFalse();
      }
    }
  }
}