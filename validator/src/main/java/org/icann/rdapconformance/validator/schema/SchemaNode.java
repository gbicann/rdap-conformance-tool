package org.icann.rdapconformance.validator.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import org.everit.json.schema.ArraySchema;
import org.everit.json.schema.CombinedSchema;
import org.everit.json.schema.ObjectSchema;
import org.everit.json.schema.ReferenceSchema;
import org.everit.json.schema.Schema;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class SchemaNode {

  private static final Logger logger = LoggerFactory.getLogger(SchemaNode.class);

  protected final SchemaNode parentNode;
  protected final Schema schema;
  protected String propertyName = "";

  protected SchemaNode(SchemaNode parentNode, Schema schema) {
    Objects.requireNonNull(schema);
    this.parentNode = parentNode;
    this.schema = schema;
  }

  public static SchemaNode create(SchemaNode parentNode, Schema schema) {
    if (schema instanceof ObjectSchema) {
      return new ObjectSchemaNode(parentNode, schema);
    } else if (schema instanceof ReferenceSchema) {
      return new ReferenceSchemaNode(parentNode, schema);
    } else if (schema instanceof ArraySchema) {
      return new ArraySchemaNode(parentNode, schema);
    } else if (schema instanceof CombinedSchema) {
      return new CombinedSchemaNode(parentNode, schema);
    } else {
      return new SimpleSchemaNode(parentNode, schema);
    }
  }

  public abstract List<SchemaNode> getChildren();

  public List<SchemaNode> getAllCombinedChildren() {
    List<SchemaNode> children = new ArrayList<>();
    return getAllCombinedChildrenRecursively(children);
  }

  List<SchemaNode> getAllCombinedChildrenRecursively(List<SchemaNode> children) {
    if (getChildren().isEmpty()) {
      children.add(this);
    } else {
      for (SchemaNode child : getChildren()) {
        if (child instanceof CombinedSchemaNode || child instanceof ReferenceSchemaNode) {
          child.getAllCombinedChildrenRecursively(children);
        } else {
          children.add(child);
        }
      }
    }
    return children;
  }

  public boolean containsErrorKey(String errorKey) {
    return schema.getUnprocessedProperties().containsKey(errorKey);
  }

  public int getErrorCode(String errorKey) {
    return (int) schema.getUnprocessedProperties().get(errorKey);
  }

  public Object getErrorKey(String errorKey) {
    return schema.getUnprocessedProperties().get(errorKey);
  }

  public Optional<ObjectSchemaNode> findParentOfNodeWith(String key) {
    List<SchemaNode> schemaNodes = getChildren();
    for (SchemaNode schemaNode : schemaNodes) {
      Optional<ObjectSchemaNode> foundNode = schemaNode.findParentOfNodeWith(key);
      if (foundNode.isPresent()) {
        return foundNode;
      }
    }

    return Optional.empty();
  }

  public Optional<SchemaNode> findChild(String key) {
    return findParentOfNodeWith(key)
        .map(p -> p.getChild(key))
        .map(c -> {
          if (c instanceof ReferenceSchemaNode) {
            return ((ReferenceSchemaNode)c).getChild();
          }
          return c;
        });
  }

  /**
   * Find the corresponding the closest error key from the searchKey in the JSON hierarchy. e.g.: {
   * "firstLevel": { "secondLevel": { "searchKey": "test" } } "errorKey": -1 } In this example,
   * since the errorKey doesn't exist at the second level, this function will take the "errorKey" =
   * -1 at the first level.
   */
  public int searchBottomMostErrorCode(String searchKey, String errorKey) {
    String unfoundError =
        "No such error key (" + errorKey + ") in the hierarchy around " + searchKey;
    Optional<SchemaNode> optNode = findChild(searchKey);
    if (optNode.isEmpty()) {
      throw new IllegalArgumentException(unfoundError);
    }
    SchemaNode node = optNode.get();
    SchemaNode parent = node;
    while (parent != null && !parent.containsErrorKey(errorKey)) {
      parent = parent.parentNode;
    }

    if (parent.containsErrorKey(errorKey)) {
      return parent.getErrorCode(errorKey);
    }

    throw new IllegalArgumentException(unfoundError);
  }

  public Schema getSchema() {
    return schema;
  }

  Optional<SchemaNode> findAssociatedSchema(String jsonPointer) {
    String[] elements = jsonPointer.split("/");
    if (elements.length < 2) {
      return Optional.empty();
    }

    SchemaNode schemaNode = this;
    int i = 1;
    do {
      try {
        Integer.parseInt(elements[i]);
      } catch (NumberFormatException e) {
        // we have a string
        String schemaName = elements[i];
        Optional<ObjectSchemaNode> node = schemaNode.findParentOfNodeWith(schemaName);
        if (node.isPresent()) {
          schemaNode = node.get().getChild(schemaName);
        } else {
          return Optional.empty();
        }
      }
      i++;
    } while (i < elements.length);

    return Optional.of(schemaNode);
  }

  public Set<ValidationNode> findValidationNodes(String jsonPointer, String validationName) {
    List<SchemaNode> schemaNodes = findAssociatedSchema(jsonPointer)
        .map(s -> s instanceof ReferenceSchemaNode ? ((ReferenceSchemaNode) s).getChild() : s)
        .map(s -> s instanceof CombinedSchemaNode ? s.getAllCombinedChildren() : List.of(s))
        .orElse(Collections.emptyList());

    Set<ValidationNode> validationNodes = new HashSet<>();
    for (SchemaNode parent : schemaNodes) {
      while (parent != null) {
        if (parent.containsErrorKey(validationName)) {
          validationNodes.add(new ValidationNode(parent, validationName));
        }
        parent = parent.parentNode;
      }
    }

    return validationNodes;
  }

  public Optional<SchemaNode> findAssociatedParentValidationNode(String validationKey) {
    SchemaNode parent = this;
    while (parent != null && !parent.containsErrorKey(validationKey)) {
      parent = parent.parentNode;
    }
    if (parent != null) {
      return Optional.of(parent);
    }
    return Optional.empty();
  }

  public JsonPointers findJsonPointersBySchemaId(String schemaId, JSONObject jsonObject) {
    Objects.requireNonNull(schemaId);
    return findById(schemaId, new HashSet<>())
        .map(schemaNode -> {

          SchemaNode parent = schemaNode;
          Stack<String> stack = new Stack<>();
          while (parent != null) {
            if (parent instanceof ArraySchemaNode) {
              stack.add("{}");
            }
            if (parent.parentNode instanceof ObjectSchemaNode) {
              stack.add(parent.propertyName);
            }
            parent = parent.parentNode;
          }

          Set<String> jsonPointers = Set.of("#");
          while (!stack.empty()) {
            String segment = stack.pop();
            Set<String> newJsonPointers = new HashSet<>();
            // this is an array:
            if (segment.equals("{}")) {
              for (String pointer : jsonPointers) {
                JSONArray jsonArray = (JSONArray) jsonObject.query(pointer);

                if (jsonArray == null) {
                  // there is no data at the jsonPointer location on the real object
                  continue;
                }

                int i = 0;
                for (Object o : jsonArray) {
                  newJsonPointers.add(pointer + "/" + i);
                  i++;
                }
              }
            } else {
              for (String pointer : jsonPointers) {
                newJsonPointers.add(pointer + "/" + segment);
              }
            }
            jsonPointers = newJsonPointers;
          }
          return new JsonPointers(jsonPointers);
        })
        .orElse(new JsonPointers());
  }

  private Optional<SchemaNode> findById(String schemaId,
      Set<String> alreadyVisitedIds) {
    Objects.requireNonNull(schemaId);
    if (schemaId.equals(schema.getId())) {
      return Optional.of(this);
    }

    if (schema.getId() != null) {
      alreadyVisitedIds.add(schema.getId());
    }

    Optional<SchemaNode> foundNode;
    for (SchemaNode schemaNode : getChildren()) {
      // jcard schema has recursive sub schemas without ids and will result in a stackoverflow:
      if (schemaNode.propertyName.equals("vcardArray")) {
        continue;
      }

      // nested schema like entity/entities should be visited once:
      if (alreadyVisitedIds.contains(schemaNode.schema.getId())) {
        continue;
      }

      foundNode = schemaNode.findById(schemaId, alreadyVisitedIds);
      if (foundNode.isPresent()) {
        return foundNode;
      }
    }

    return Optional.empty();
  }

  private List<SchemaNode> findAllChildren(List<SchemaNode> schemaNodes,
      Set<String> alreadyVisitedIds) {
    if (schema.getId() != null) {
      alreadyVisitedIds.add(schema.getId());
    }

    schemaNodes.add(this);

    for (SchemaNode schemaNode : getChildren()) {
      // jcard schema has recursive sub schemas without ids and will result in a stackoverflow:
      if (schemaNode.propertyName.equals("vcardArray") ||
          // nested schema like entity/entities should be visited once:
          alreadyVisitedIds.contains(schemaNode.schema.getId())
      ) {
        continue;
      }

      schemaNode.findAllChildren(schemaNodes, alreadyVisitedIds);
    }

    return schemaNodes;
  }

  public Set<String> findAllValuesOf(String key) {
    Objects.requireNonNull(key);
    Set<String> values = new HashSet<>();
    List<SchemaNode> allSchemaNodes = findAllChildren(new ArrayList<>(), new HashSet<>());
    for (SchemaNode schemaNode : allSchemaNodes) {
      if (schemaNode.containsErrorKey(key)) {
        values.add((String)schemaNode.getErrorKey(key));
      }
    }
    return values;
  }
}
