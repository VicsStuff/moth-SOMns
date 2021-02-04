/**
 * Copyright (c) 2018 Richard Roberts, richard.andrew.roberts@gmail.com
 * Victoria University of Wellington, Wellington New Zealand
 * http://gracelang.org/applications/home/
 *
 * Copyright (c) 2013 Stefan Marr,     stefan.marr@vub.ac.be
 * Copyright (c) 2009 Michael Haupt,   michael.haupt@hpi.uni-potsdam.de
 * Software Architecture Group, Hasso Plattner Institute, Potsdam, Germany
 * http://www.hpi.uni-potsdam.de/swa/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package som.compiler;

import static som.vm.Symbols.symbolFor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import bd.tools.structure.StructuralProbe;
import som.compiler.MixinDefinition.SlotDefinition;
import som.interpreter.SomLanguage;
import som.interpreter.nodes.ExpressionNode;
import som.interpreter.nodes.LocalVariableNode;
import som.interpreter.nodes.MessageSendNode.AbstractMessageSendNode;
import som.vm.VmSettings;
import som.vmobjects.SInvokable;
import som.vmobjects.SSymbol;


/**
 * The JSON Tree Translator is responsible for creating SOM AST from {@link #jsonAST} (a JSON
 * representation of Grace AST) and also for extracting Grace's type information.
 *
 * The translator walks through each node of the JSON AST and uses the {@link AstBuilder} to
 * generate the equivalent AST for each node. The AstBuilder may invoke the parse method on
 * this translator (enabling recursive descent through the JSON AST), but should not interact
 * with this translator otherwise.
 */
public class JsonTreeTranslator {

  private final SomLanguage language;

  private final ScopeManager  scopeManager;
  private final SourceManager sourceManager;

  private final AstBuilder astBuilder;
  private final JsonObject jsonAST;

  public JsonTreeTranslator(final JsonObject jsonAST, final Source source,
      final SomLanguage language,
      final StructuralProbe<SSymbol, MixinDefinition, SInvokable, SlotDefinition, Variable> probe) {
    this.language = language;

    this.scopeManager = new ScopeManager(language, probe);
    this.sourceManager = new SourceManager(language, source);

    this.astBuilder = new AstBuilder(this, scopeManager, sourceManager, language, probe);
    this.jsonAST = jsonAST;
  }

  /**
   * Uses the {@link SourceManager} to create a section corresponding to the source code at the
   * given line and column.
   */
  public SourceSection source(final JsonObject node) {
    int line = node.get("line").getAsInt();
    int column = node.get("column").getAsInt();
    if (line < 1) {
      line = 1;
    }
    if (column < 1) {
      column = 1;
    }
    return sourceManager.atLineColumn(line, column);
  }

  private void error(final String message, final JsonObject node) {
    String prefix = "";
    if (node != null) {
      int line = node.get("line").getAsInt();
      int column = node.get("column").getAsInt();
      prefix = "[" + sourceManager.getModuleName() + " " + line + "," + column + "] ";
    }
    language.getVM().errorExit(prefix + message);
  }

  /**
   * Gets the type of a {@link JsonObject}, which should be a string stored in the "nodetype"
   * field.
   */
  private String nodeType(final JsonObject node) {
    return node.get("nodetype").getAsString();
  }

  /**
   * Gets the body of a {@link JsonObject}, which should be a {@link JsonArray} stored in the
   * "body" field.
   */
  private JsonArray body(final JsonObject node) {
    return node.get("body").getAsJsonArray();
  }

  /**
   * Gets the name of a {@link JsonObject}, which should be a string stored in the "name"
   * field.
   */
  private String name(final JsonObject node) {
    if (node.has("name")) {
      if (node.get("name").isJsonObject()) {
        return name(node.get("name").getAsJsonObject());
      } else {
        return node.get("name").getAsString();
      }

    } else if (node.has("basename")) {
      return name(node.get("basename").getAsJsonObject());

    } else if (node.has("left")) {
      return name(node.get("left").getAsJsonObject());

    } else if (node.has("from")) {
      return name(node.get("from").getAsJsonObject());

    } else if (nodeType(node).equals("explicit-receiver-request")) {
      return name(node.get("parts").getAsJsonArray().get(0).getAsJsonObject());

    } else {
      error("The translator doesn't understand how to get a name from " + nodeType(node),
          node);
      throw new RuntimeException();
    }
  }

  /**
   * Generates a munged class name based on the class named in the given inherits expressions.
   * The format of the munged name is:
   *
   * <name><suffix>[Class]
   *
   * where name is the class name as it is and suffix is a series of `:` (one for each argument
   * in the inherits expression).
   */
  private SSymbol className(final JsonObject node) {
    String suffix = "";
    for (int i = 0; i < arguments(node).length; i++) {
      suffix += ":";
    }
    return symbolFor(name(node) + suffix + "[Class]");
  }

  /**
   * Gets the value of the path field in a {@link JsonObject}.
   */
  private String path(final JsonObject node) {
    if (node.get("path").isJsonObject()) {
      return node.get("path").getAsJsonObject().get("raw").getAsString();
    } else {
      error("The translator doesn't understand how to get a path from " + nodeType(node),
          node);
      throw new RuntimeException();
    }
  }

  /**
   * Calculates the number of arguments associated with a part (a part of a request node).
   */
  private int countArgumentsOrParametersInPart(final JsonObject part) {
    if (part.has("arguments")) {
      return part.get("arguments").getAsJsonArray().size();
    } else if (part.has("parameters")) {
      return part.get("parameters").getAsJsonArray().size();
    } else {
      error(
          "The translator doesn't understand how to count the arguments or parameters for the part",
          part);
      throw new RuntimeException();
    }

  }

  /**
   * Gets the selector from an array of "parts", each of which contains a "name" field hosting
   * a string value. Each part also has an "arguments" field. Each argument should represented
   * by a `:` separator.
   */
  private SSymbol selectorFromParts(final JsonArray parts) {
    String selector = "";
    for (JsonElement element : parts) {
      JsonObject part = element.getAsJsonObject();
      selector += name(part);

      for (int i = 0; i < countArgumentsOrParametersInPart(part); i++) {
        selector += ":";
      }
    }

    return symbolFor(selector);
  }

  /**
   * Gets the receiver for a request, which should be stored in the `receiver` field.
   */
  private JsonObject receiver(final JsonObject node) {
    if (node.has("receiver")) {
      return node.get("receiver").getAsJsonObject();

    } else if (node.has("left")) {
      return node.get("left").getAsJsonObject();

    } else {
      error("The translator doesn't understand how to get a receiver from " + nodeType(node),
          node);
      throw new RuntimeException();
    }
  }

  /**
   * Gets the return type of an AST node with a signature.
   *
   * @param node - class or method definition
   * @return The AST of the return type expression. Null if the type is unknown (possibly by
   *         being undefined).
   */
  private JsonObject returnType(final JsonObject node) {
    if (!VmSettings.USE_TYPE_CHECKING) { // simply return null if type checking not used
      return null;
    }
    // Check that the signature has a return type
    JsonObject signatureNode = node.get("signature").getAsJsonObject();
    if (signatureNode.get("returntype").isJsonNull()) {
      // Report an error if a type is expected
      if (VmSettings.MUST_BE_FULLY_TYPED) {
        error(nodeType(node) + " is missing a type annotation", node);
        throw new RuntimeException();
      }
      // Otherwise it is unknown
      return null; // SomStructuralType.UNKNOWN;
    } else {
      // Return the AST of the return type
      return signatureNode.get("returntype").getAsJsonObject();

    }
  }

  /**
   * Maps a Grace prefix operator to a NS operator or method call.
   *
   * TODO: prefix operators may be defined on non-primitive objects. Consequently, this mapping
   * should be handled dynamically.
   */
  private SSymbol prefixOperatorFor(final String name) {
    if (name.equals("prefix!") || name.equals("!")) {
      return symbolFor("not");

    } else if (name.equals("prefix-") || name.equals("-")) {
      return symbolFor("negated");
    } else if (name.equals("prefix<") || name.equals("<")) {
      return symbolFor("lessThan");
    } else if (name.equals("prefix>") || name.equals(">")) {
      return symbolFor("greaterThan");
    } else if (name.equals("prefix<=") || name.equals("<=")) {
      return symbolFor("lessEqualThan");
    } else if (name.equals("prefix>=") || name.equals(">=")) {
      return symbolFor("greaterEqualThan");
    } else {
      error("The translator doesn't understand what to do with the `" + name
          + "` prefix operator", null);
      throw new RuntimeException();
    }
  }

  /**
   * Gets the selector for an array of parts, given either from a request or a declaration.
   *
   * Note that signatures defined for Grace's built in objects map directly onto other defined
   * for SOM's built in objects. We change to the SOM signature in the cases to take advantage
   * of this mapping.
   */
  private SSymbol processSelector(final SSymbol selector) {
    if (isOperator(selector.getString().replace(":", ""))) {
      return symbolFor(selector.getString().replace(":", ""));
    }

    return selector;
  }

  private SSymbol selector(final JsonObject node) {
    SSymbol selector;
    if (node.has("parts")) {
      selector = selectorFromParts(node.get("parts").getAsJsonArray());

    } else if (node.has("signature")) {
      selector = selectorFromParts(
          node.get("signature").getAsJsonObject().get("parts").getAsJsonArray());

    } else if (nodeType(node).equals("prefix-operator")) {
      selector = prefixOperatorFor(name(node));

    } else if (nodeType(node).equals("bind")) {
      selector = selector(node.get("left").getAsJsonObject());

    } else if (node.has("operator")) {
      String operator = node.get("operator").getAsString();
      selector = symbolFor(operator);

    } else {
      error(
          "The translator doesn't understand how to get a selector from an " + nodeType(node),
          node);
      throw new RuntimeException();
    }

    return processSelector(selector);
  }

  /**
   * Gets a list of arguments nodes, each represented by a {@link JsonObject}, from a request
   * node by iterating through the arguments belonging to each part of that request.
   */
  private JsonObject[] argumentsFromParts(final JsonArray parts) {
    List<JsonObject> argumentsNodes = new ArrayList<JsonObject>();

    for (JsonElement partElement : parts) {
      JsonObject part = partElement.getAsJsonObject();
      for (JsonElement argumentElement : part.get("arguments").getAsJsonArray()) {
        argumentsNodes.add(argumentElement.getAsJsonObject());
      }
    }

    return argumentsNodes.toArray(new JsonObject[argumentsNodes.size()]);
  }

  /**
   * Gets the arguments for a request node.
   */
  private JsonObject[] arguments(final JsonObject node) {
    if (node.has("parts")) {
      return argumentsFromParts(node.get("parts").getAsJsonArray());

    } else if (node.has("arguments")) {
      JsonArray args = node.get("arguments").getAsJsonArray();
      JsonObject[] ret = new JsonObject[args.size()];
      for (int i = 0; i < args.size(); i++) {
        ret[i] = args.get(i).getAsJsonObject();
      }
      return ret;

    } else if (node.has("right")) {
      return new JsonObject[] {node.get("right").getAsJsonObject()};

    } else if (nodeType(node).equals("inherits")) {
      JsonObject from = node.get("from").getAsJsonObject();
      if (from.has("parts")) {
        return argumentsFromParts(from.get("parts").getAsJsonArray());
      } else {
        return new JsonObject[] {};
      }

    } else {
      error(
          "The translator doesn't understand how to get arguments from a " + nodeType(node)
              + "node",
          node);
      throw new RuntimeException();
    }
  }

  /**
   * Gets the parameters for a declaration node.
   */
  private SSymbol[] parameters(final JsonObject node) {
    List<SSymbol> parametersNames = new ArrayList<SSymbol>();

    if (node.has("signature")) {
      JsonArray parts = node.get("signature").getAsJsonObject().get("parts").getAsJsonArray();
      for (JsonElement partElement : parts) {
        JsonObject part = partElement.getAsJsonObject();
        for (JsonElement parameterElement : part.get("parameters").getAsJsonArray()) {
          SSymbol name = symbolFor(name(parameterElement.getAsJsonObject()));
          parametersNames.add(name);
        }
      }

    } else if (node.has("parameters")) {
      for (JsonElement parameterElement : node.get("parameters").getAsJsonArray()) {
        SSymbol name = symbolFor(name(parameterElement.getAsJsonObject()));
        parametersNames.add(name);
      }

    } else {
      error("The translator doesn't understand how to get parameters from " + node, node);
      throw new RuntimeException();
    }

    return parametersNames.toArray(new SSymbol[parametersNames.size()]);
  }

  /**
   * Extracts the name of type declared inside of the given node, which may be either a
   * typed-parameter or otherwise a simple identifier.
   *
   * @return The type expression for the node. Null if the type is unknown (or undefined).
   */
  private JsonObject typeFor(final JsonObject node) {
    return typeFor(node, VmSettings.USE_TYPE_CHECKING);
  }

  private JsonObject typeFor(final JsonObject node, final boolean usingTypes) {
    // simply return null if type checking not used
    if (!usingTypes) {
      return null;
    }

    String nodeType = nodeType(node);

    if (nodeType.equals("typed-parameter")) {
      return node.get("type").getAsJsonObject();
    } else if (nodeType.equals("identifier")) {
      // no op (returns unknown)
    } else if (node.has("type")) {
      // Return the type if it has one
      if (node.get("type").isJsonObject()) {
        return node.get("type").getAsJsonObject();
      }
    } else {
      error("The translator doesn't understand how to get type for " + nodeType, node);
      throw new RuntimeException();
    }
    // Throw an error if a type was required
    if (VmSettings.MUST_BE_FULLY_TYPED) {
      error(nodeType + " is missing a type annotation", node);
      throw new RuntimeException();
    }
    // Return that the type is unknown
    return null;
  }

  /**
   * Gets the parameter types for a declaration node.
   */
  private JsonObject[] typesForParameters(final JsonObject node) {
    return typesForParameters(node, VmSettings.USE_TYPE_CHECKING);
  }

  private JsonObject[] typesForBlockParameters(final JsonObject node) {
    List<JsonObject> types = new ArrayList<JsonObject>();
    for (JsonElement parameterElement : node.get("parameters").getAsJsonArray()) {
      String type = parameterElement.getAsJsonObject().get("nodetype").getAsString();
      // Only add the parameter if it is an argument (and not a pattern)
      if ("typed-parameter".equals(type) || "identifier".equals(type)) {
        types.add(typeFor(parameterElement.getAsJsonObject(), VmSettings.USE_TYPE_CHECKING));
      }
    }
    return types.toArray(new JsonObject[types.size()]);
  }

  private JsonObject patternForParameter(final JsonObject node) {
    JsonArray params = node.get("parameters").getAsJsonArray();
    if (params.size() == 0) {
      return null;
    }
    JsonObject parameterElement = params.get(0).getAsJsonObject();
    String type = parameterElement.get("nodetype").getAsString();
    if ("identifier".equals(type)) {
      // Argument with no type, should match anything
      return null;
    } else if ("typed-parameter".equals(type)) {
      return typeFor(parameterElement, true);
    } else {
      return parameterElement;
    }
  }

  private JsonObject[] typesForParameters(final JsonObject node, final boolean usingTypes) {
    List<JsonObject> types = new ArrayList<JsonObject>();

    if (node.has("signature")) {
      for (JsonElement partElement : node.get("signature").getAsJsonObject().get("parts")
                                         .getAsJsonArray()) {
        JsonObject partObject = partElement.getAsJsonObject();

        for (JsonElement parameterElement : partObject.get("parameters").getAsJsonArray()) {
          JsonObject parameterObject = parameterElement.getAsJsonObject();
          types.add(typeFor(parameterObject, usingTypes));
        }
      }

    } else if (node.has("parameters")) {
      for (JsonElement parameterElement : node.get("parameters").getAsJsonArray()) {
        types.add(typeFor(parameterElement.getAsJsonObject(), usingTypes));
      }

    } else {
      error("The translator doesn't understand how to get the types for parameters from a "
          + nodeType(node), node);
      throw new RuntimeException();
    }

    return types.toArray(new JsonObject[types.size()]);
  }

  private SourceSection[] sourcesForBlockParameters(final JsonObject node) {
    List<SourceSection> sources = new ArrayList<SourceSection>();
    for (JsonElement parameterElement : node.get("parameters").getAsJsonArray()) {
      String type = parameterElement.getAsJsonObject().get("nodetype").getAsString();
      // Only add the parameter if it is an argument (and not a pattern)
      if ("typed-parameter".equals(type) || "identifier".equals(type)) {
        sources.add(source(parameterElement.getAsJsonObject()));
      }
    }
    return sources.toArray(new SourceSection[sources.size()]);
  }

  private SSymbol[] blockParameters(final JsonObject node) {
    List<SSymbol> parametersNames = new ArrayList<SSymbol>();

    for (JsonElement parameterElement : node.get("parameters").getAsJsonArray()) {
      String type = parameterElement.getAsJsonObject().get("nodetype").getAsString();
      // Only add the parameter if it is an argument (and not a pattern)
      if ("typed-parameter".equals(type) || "identifier".equals(type)) {
        SSymbol name = symbolFor(name(parameterElement.getAsJsonObject()));
        parametersNames.add(name);
      }
    }
    return parametersNames.toArray(new SSymbol[parametersNames.size()]);
  }

  /**
   * Gets the parameter sources for a declaration node.
   */
  private SourceSection[] sourcesForParameters(final JsonObject node) {
    List<SourceSection> sources = new ArrayList<SourceSection>();

    if (node.has("signature")) {
      for (JsonElement partElement : node.get("signature").getAsJsonObject().get("parts")
                                         .getAsJsonArray()) {
        JsonObject partObject = partElement.getAsJsonObject();

        for (JsonElement parameterElement : partObject.get("parameters").getAsJsonArray()) {
          JsonObject parameterObject = parameterElement.getAsJsonObject();
          sources.add(source(parameterObject));
        }
      }

    } else if (node.has("parameters")) {
      for (JsonElement parameterElement : node.get("parameters").getAsJsonArray()) {
        sources.add(source(parameterElement.getAsJsonObject()));
      }

    } else {
      error(
          "The translator doesn't understand how to get sources for the parameters in a "
              + nodeType(node),
          node);
      throw new RuntimeException();
    }

    return sources.toArray(new SourceSection[sources.size()]);
  }

  private SSymbol[] locals(final JsonObject node) {
    List<SSymbol> localNames = new ArrayList<SSymbol>();
    for (JsonElement element : body(node)) {
      String type = nodeType(element.getAsJsonObject());
      if (type.equals("def-declaration") || type.equals("var-declaration")) {
        localNames.add(symbolFor(name(element.getAsJsonObject())));
      }
    }
    return localNames.toArray(new SSymbol[localNames.size()]);
  }

  private JsonObject[] typesForLocals(final JsonObject node) {
    List<JsonObject> types = new ArrayList<JsonObject>();
    for (JsonElement element : body(node)) {
      JsonObject eNode = element.getAsJsonObject();
      String type = nodeType(element.getAsJsonObject());
      if (type.equals("def-declaration") || type.equals("var-declaration")) {
        types.add(typeFor(eNode));
      }
    }
    return types.toArray(new JsonObject[types.size()]);
  }

  /**
   * Gets a mapping between the nth local and whether it is a def statement.
   *
   * @param node
   * @return An array representing the nth local and if it is a def.
   */
  private boolean[] isDefForLocals(final JsonObject node) {
    List<Boolean> isDefsList = new ArrayList<Boolean>();
    for (JsonElement element : body(node)) {
      String type = nodeType(element.getAsJsonObject());
      if (type.equals("def-declaration")) {
        isDefsList.add(true);
      } else if (type.equals("var-declaration")) {
        isDefsList.add(false);
      }
    }
    boolean[] isDefs = new boolean[isDefsList.size()];
    int i = 0;
    for (boolean isDef : isDefsList) {
      isDefs[i++] = isDef;
    }
    return isDefs;
  }

  private SourceSection[] sourcesForLocals(final JsonObject node) {
    List<SourceSection> sourceSections = new ArrayList<SourceSection>();
    for (JsonElement element : body(node)) {
      JsonObject object = element.getAsJsonObject();
      String type = nodeType(object);
      if (type.equals("def-declaration") || type.equals("var-declaration")) {
        sourceSections.add(source(object));
      }
    }
    return sourceSections.toArray(new SourceSection[sourceSections.size()]);
  }

  /**
   * Determines whether the given signature is an operator (true when the signature is composed
   * of only one or more of Grace's operator symbols).
   */
  private boolean isOperator(final String signature) {
    return signature.matches("[+\\-*/<>]+");
  }

  private SSymbol[] parseInterfaceSignatures(final JsonObject node) {
    Set<SSymbol> signatures = new HashSet<>();

    JsonArray signatureNodes = node.get("body").getAsJsonArray();
    for (JsonElement signatureElement : signatureNodes) {
      JsonObject signatureNode = signatureElement.getAsJsonObject();
      SSymbol signature = selectorFromParts(signatureNode.get("parts").getAsJsonArray());

      if (signature.getString().startsWith("prefix")) {
        signatures.add(prefixOperatorFor(signature.getString()));

      } else if (isOperator(signature.getString().replace(":", ""))) {
        signatures.add(symbolFor(signature.getString().replace(":", "")));

      } else {
        signatures.add(signature);
      }

    }

    return signatures.toArray(new SSymbol[] {});
  }

  /**
   * Builds an explicit send by translating the receiver and the arguments of the given
   * request node.
   */
  public ExpressionNode explicit(final SSymbol selector, final JsonObject receiver,
      final JsonObject[] arguments, final SourceSection source) {

    // Translate the receiver
    ExpressionNode translateReceiver = translate(receiver);

    // Translate the arguments
    List<ExpressionNode> argumentExpressions = new ArrayList<ExpressionNode>();
    for (int i = 0; i < arguments.length; i++) {
      ExpressionNode argumentExpression = translate(arguments[i]);
      argumentExpressions.add(argumentExpression);
    }

    return astBuilder.requestBuilder.explicit(selector, translateReceiver, argumentExpressions,
        source);
  }

  public ExpressionNode explicitAssignment(final SSymbol selector, final JsonObject receiver,
      final JsonObject[] arguments, final SourceSection source) {
    SSymbol setterSelector = symbolFor(selector.getString() + ":");
    return explicit(setterSelector, receiver, arguments, source);
  }

  /**
   * Creates either a variable read (when no arguments are provided) or a message send (when
   * arguments are provided) using the method currently at the top of the stack.
   */
  public ExpressionNode implicit(final SSymbol selector,
      final JsonObject[] argumentsNodes, final SourceSection sourceSection) {

    // If no arguments are provided, it's a implicit requests that can be made directly via
    // the method
    if (argumentsNodes.length == 0) {
      return astBuilder.requestBuilder.implicit(selector, sourceSection);
    }

    // Otherwise, process the arguments and create a message send.
    List<ExpressionNode> arguments = new ArrayList<ExpressionNode>();
    for (int i = 0; i < argumentsNodes.length; i++) {
      arguments.add(translate(argumentsNodes[i]));
    }

    // Create the message send with information from the current method
    return astBuilder.requestBuilder.implicit(selector, arguments, sourceSection);
  }

  /**
   * The re-entry point for the translator, which continues the translation from the given
   * node. This method should be used by the {@link AstBuilder} in a recursive-descent style.
   */
  public ExpressionNode translate(final JsonObject node) {
    if (node == null) {
      return null; // TODO: Should this case exist?
      // Ignore comments, no expression
    } else if (nodeType(node).equals("comment")) {
      return null;
      // Add a method, no expression
    } else if (nodeType(node).equals("method-declaration")) {
      astBuilder.objectBuilder.method(selector(node), returnType(node), parameters(node),
          typesForParameters(node), sourcesForParameters(node), locals(node),
          typesForLocals(node), isDefForLocals(node), sourcesForLocals(node),
          body(node), source(node));
      return null;
      // Add a class and the factory method, no expression
    } else if (nodeType(node).equals("class-declaration")) {
      SSymbol selector = selector(node);
      SSymbol[] parameters = parameters(node);
      astBuilder.objectBuilder.clazzDefinition(selector, returnType(node), parameters,
          typesForParameters(node),
          sourcesForParameters(node),
          locals(node), typesForLocals(node), isDefForLocals(node), sourcesForLocals(node),
          body(node),
          source(node));
      astBuilder.objectBuilder.clazzMethod(selector, returnType(node), parameters,
          typesForParameters(node),
          sourcesForParameters(node), source(node));
      return null;
      // Translate an object literal
    } else if (nodeType(node).equals("object")) {
      return astBuilder.objectBuilder.objectConstructor(locals(node), typesForLocals(node),
          isDefForLocals(node), sourcesForLocals(node), body(node), source(node));
      // Add a method defining a type, no expression
    } else if (nodeType(node).equals("type-statement")) {
      astBuilder.objectBuilder.typeStatement(symbolFor(name(node)),
          translate((JsonObject) node.get("body")), source(node));
      return null;
      // Translate a block literal
    } else if (nodeType(node).equals("block")) {
      return astBuilder.objectBuilder.block(blockParameters(node), patternForParameter(node),
          typesForBlockParameters(node),
          sourcesForBlockParameters(node), locals(node), typesForLocals(node),
          isDefForLocals(node), sourcesForLocals(node), body(node), source(node));
      // Translate a def
    } else if (nodeType(node).equals("def-declaration")) {
      // As an assignment if it is a local variable
      ExpressionNode en = astBuilder.requestBuilder.assignment(symbolFor(name(node)),
          translate(node.get("value").getAsJsonObject()), source(node));
      if (en instanceof LocalVariableNode) {
        return en;
      }
      // Otherwise it is an initializer for a slot
      return astBuilder.objectBuilder.slotInitializer(symbolFor(name(node)),
          translate(typeFor(node)), translate(node.get("value").getAsJsonObject()),
          source(node));
      // Translate a var
    } else if (nodeType(node).equals("var-declaration")) {
      // AS no expression if just a declaration
      if (node.get("value").isJsonNull()) {
        return null;
        // As an assignment
      } else {
        return astBuilder.requestBuilder.assignment(symbolFor(name(node)),
            translate(node.get("value").getAsJsonObject()),
            source(node));
      }
    } else if (nodeType(node).equals("identifier")) {
      return astBuilder.requestBuilder.implicit(symbolFor(name(node)), source(node));

    } else if (nodeType(node).equals("implicit-receiver-request")) {
      return implicit(selector(node), arguments(node), source(node));

    } else if (nodeType(node).equals("explicit-receiver-request")) {
      return explicit(selector(node), receiver(node), arguments(node), source(node));

    } else if (nodeType(node).equals("bind")) {
      if (nodeType(node.get("left").getAsJsonObject()).equals("explicit-receiver-request")) {
        return explicitAssignment(selector(node), receiver(node.get("left").getAsJsonObject()),
            arguments(node), source(node));
      } else {
        return astBuilder.requestBuilder.assignment(symbolFor(name(node)),
            translate(node.get("right").getAsJsonObject()), source(node));
      }

    } else if (nodeType(node).equals("operator")) {
      return explicit(selector(node), receiver(node), arguments(node), source(node));

    } else if (nodeType(node).equals("prefix-operator")) {
      return explicit(selector(node), receiver(node), new JsonObject[] {}, source(node));

    } else if (nodeType(node).equals("parenthesised")) {
      return translate(node.get("expression").getAsJsonObject());

    } else if (nodeType(node).equals("return")) {
      ExpressionNode returnExpression;
      if (node.get("returnvalue").isJsonNull()) {
        returnExpression = astBuilder.literalBuilder.done(source(node));
      } else {
        returnExpression =
            translate(node.get("returnvalue").getAsJsonObject());
      }

      if (scopeManager.peekMethod().isBlockMethod()) {
        return astBuilder.requestBuilder.makeBlockReturn(returnExpression, source(node));
      } else {
        return returnExpression;
      }

    } else if (nodeType(node).equals("inherits")) {
      JsonObject from = node.get("from").getAsJsonObject();
      if (nodeType(from).equals("explicit-receiver-request")) {
        MixinBuilder builder = scopeManager.peekObject();
        scopeManager.pushMethod(builder.getClassInstantiationMethodBuilder());

        ExpressionNode e =
            explicit(selector(from), receiver(from), arguments(from), source(from));
        AbstractMessageSendNode req = (AbstractMessageSendNode) e;
        req.addSuffixToSelector("[Class]");
        astBuilder.objectBuilder.setInheritanceByExpression(req, arguments(from),
            source(node));

        scopeManager.popMethod();

      } else {
        astBuilder.objectBuilder.setInheritanceByName(className(node), arguments(node),
            source(node));
      }
      return null;

    } else if (nodeType(node).equals("import")) {
      String path = path(node);
      try {
        language.getVM().loadModule(sourceManager.pathForModuleNamed(symbolFor(path)));
      } catch (IOException e) {
        e.printStackTrace();
        error("An error was thrown when eagerly parsing " + path, node);
        throw new RuntimeException();
      }
      ExpressionNode importExpression =
          astBuilder.requestBuilder.importModule(symbolFor(path), source(node));
      astBuilder.objectBuilder.addImmutableSlot(symbolFor(name(node)), null, importExpression,
          source(node));
      return null;
      // Translate an interface type literal
    } else if (nodeType(node).equals("interface")) {
      SSymbol[] signatures = parseInterfaceSignatures(node);
      return astBuilder.literalBuilder.type(signatures, source(node));
    } else if (nodeType(node).equals("number")) {
      String num = node.get("digits").getAsString();
      if (num.contains(".")) {
        double value = Double.parseDouble(num);
        return astBuilder.literalBuilder.number(value, source(node));
      } else {
        long value = Long.parseLong(num);
        return astBuilder.literalBuilder.number(value, source(node));
      }
    } else if (nodeType(node).equals("string-literal")) {
      return astBuilder.literalBuilder.string(node.get("raw").getAsString(), source(node));

    } else if (nodeType(node).equals("interpolated-string")) {
      return astBuilder.requestBuilder.interpolatedString(node.get("parts").getAsJsonArray());

    } else if (nodeType(node).equals("implicit-bracket-request")) {
      return astBuilder.literalBuilder.array(arguments(node), source(node));

    } else {
      error("The translator doesn't understand what to do with a " + nodeType(node) + " node?",
          node);
      throw new RuntimeException();
    }
  }

  /**
   * The entry point for the translator, which begins the translation at the module level.
   *
   * The body of the module will be added to the initialization method for all modules expect
   * the main module, in which case those expressions are added to main (so that the system
   * arguments are available).
   */
  public MixinDefinition translateModule() {
    JsonObject moduleNode = jsonAST.get("module").getAsJsonObject();
    MixinDefinition result = astBuilder.objectBuilder.module(locals(moduleNode),
        typesForLocals(moduleNode), isDefForLocals(moduleNode), sourcesForLocals(moduleNode),
        body(moduleNode),
        source(moduleNode));
    return result;
  }
}
