package org.icann.rdapconformance.validator;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaClient;
import org.everit.json.schema.loader.SchemaLoader;
import org.icann.rdapconformance.validator.exception.parser.ExceptionParser;
import org.icann.rdapconformance.validator.schema.SchemaNode;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class SchemaValidator {

  static Pattern duplicateKeys = Pattern.compile("Duplicate key \"(.+)\" at");
  private final JSONObject schemaObject;
  private final Schema schema;
  private final RDAPValidatorContext context;
  private final SchemaNode schemaRootNode;

  public SchemaValidator(String schemaName, RDAPValidatorContext context) {
    this.schema = getSchema(schemaName, "json-schema/", getClass().getClassLoader());
    this.schemaRootNode = SchemaNode.create(null, this.schema);
    this.schemaObject = new JSONObject(schema.toString());
    this.context = context;
  }

  public static Schema getSchema(String name, String scope, ClassLoader classLoader) {
    JSONObject jsonSchema = new JSONObject(
        new JSONTokener(
            Objects.requireNonNull(
                classLoader.getResourceAsStream(scope + name))));
    SchemaLoader schemaLoader = SchemaLoader.builder()
        .schemaClient(SchemaClient.classPathAwareClient())
        .schemaJson(jsonSchema)
        .resolutionScope("classpath://" + scope)
        .draftV7Support()
        .build();
    return schemaLoader.load().build();
  }

  public boolean validate(String content) {
    JSONObject jsonObject;
    try {
      jsonObject = new JSONObject(content);
    } catch (JSONException e) {
      RDAPValidationResult result = parseJsonException(e, content);
      context.addResult(result);
      return false;
    }

    try {
      schema.validate(jsonObject);
    } catch (ValidationException e) {
      parseException(e, jsonObject);
      return false;
    }
    return true;
  }

  private RDAPValidationResult parseJsonException(JSONException e, String content) {
    Matcher duplicateKeysMatcher = duplicateKeys.matcher(e.getMessage());
    if (duplicateKeysMatcher.find()) {
      String key = duplicateKeysMatcher.group(1);
      Matcher valueMatcher = Pattern.compile(key + "\":\\s*\"(.*?)\",").matcher(content);
      String value = "...";
      if (valueMatcher.find()) {
        value = valueMatcher.group(1).trim();
      }

      return RDAPValidationResult.builder()
          .code(schemaRootNode.searchBottomMostErrorCode(key, "duplicateKeys"))
          .value(key + ":" + value)
          .message("The name in the name/value pair of a link structure was found more than once.")
          .build();
    }

    return RDAPValidationResult.builder()
        .code(getErrorCode("structureInvalid"))
        .value(content)
        .message("The " + schema.getTitle() + " structure is not syntactically valid.")
        .build();
  }

  private void parseException(ValidationException e, JSONObject jsonObject) {
    List<ExceptionParser> exceptionParsers = ExceptionParser.createParsers(e, schema, jsonObject,
        context);
    for (ExceptionParser exceptionParser : exceptionParsers) {
      exceptionParser.parse();
    }
  }

  private int getErrorCode(String validationName) {
    return (int) schemaObject.get(validationName);
  }
}