/*
 * Copyright 2017-2020 FIX Protocol Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package io.fixprotocol.orchestra.quickfix;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import io.fixprotocol._2020.orchestra.repository.CodeSetType;
import io.fixprotocol._2020.orchestra.repository.CodeType;
import io.fixprotocol._2020.orchestra.repository.ComponentRefType;
import io.fixprotocol._2020.orchestra.repository.ComponentType;
import io.fixprotocol._2020.orchestra.repository.FieldRefType;
import io.fixprotocol._2020.orchestra.repository.FieldType;
import io.fixprotocol._2020.orchestra.repository.GroupRefType;
import io.fixprotocol._2020.orchestra.repository.GroupType;
import io.fixprotocol._2020.orchestra.repository.MessageType;
import io.fixprotocol._2020.orchestra.repository.Repository;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Generates message classes for QuickFIX/J from a FIX Orchestra file
 * <p>
 * Unlike the QuickFIX/J code generator, this utility works directly from a FIX
 * Orchestra file rather than from a QuickFIX data dictionary file.
 * <p>
 * For now, message validation in QuickFIX/J still requires a data dictionary
 * file, but in future versions, validations may be delegated to additional
 * generated code that takes advantage to conditional logic supported by
 * Orchestra. For example, a validator may invoke an evaluation of an expression
 * for a conditionally required field.
 *
 * @author Don Mendelson
 *
 */
public class CodeGeneratorJ {

	private static final String QUICKFIX = "quickfix";
	private static final String COMPONENT = "component";
	private static final String FIXT11 = "fixt11";
	public static final int COMPONENT_ID_STANDARD_TRAILER = 1025;
	public static final int COMPONENT_ID_STANDARD_HEADER = 1024;
	public static final int GRP_HOP_GRP = 2085;
	public static final int GRP_MSG_TYPE_GRP = 2098;
	private static final Set<BigInteger> GROUPS_EXCLUSIVE_TO_SESSION = new HashSet<BigInteger>(Arrays.asList(BigInteger.valueOf(GRP_HOP_GRP), BigInteger.valueOf(GRP_MSG_TYPE_GRP)));
	
	private static final int FAIL_STATUS = 1;
	private static final String DOUBLE_FIELD = "DoubleField";
	private static final String DECIMAL_FIELD = "DecimalField";

	private static final String FIXT_1_1 = "FIXT.1.1";

	private static final String FIX_LATEST = "FIX.Latest";

	private static final List<String> DATE_TYPES = Arrays.asList("UTCTimestamp", "UTCTimeOnly", "UTCDateOnly",
			"LocalMktDate", "LocalMktTime");

	private static final String FIELD_PACKAGE  = "quickfix.field";

	private static final long SERIALIZATION_VERSION = 552892318L;

	private static final int SPACES_PER_LEVEL = 2;
	
	private boolean isGenerateBigDecimal = true;
	private boolean isGenerateMessageBaseClass = false;
	private boolean isExcludeSession = false;
	private boolean isGenerateFixt11Package = true;

	
	/**
	 * Runs a CodeGeneratorJ with command line arguments
	 *
	 * @param args command line arguments. The first argument is the name of a FIX
	 *             Orchestra file. An optional second argument is the target
	 *             directory for generated code. It defaults to
	 *             "target/generated-sources".
	 */
	public static void main(String[] args) {
		final CodeGeneratorJ generator = new CodeGeneratorJ();
		Options options = new Options();
		new CommandLine(options).execute(args);
		try (FileInputStream inputStream = new FileInputStream(new File(options.orchestraFileName))) {
			generator.setGenerateBigDecimal(!options.isDisableBigDecimal);
			generator.setGenerateMessageBaseClass(options.isGenerateMessageBaseClass);
			if (generator.isExcludeSession && generator.isGenerateFixt11Package) {
				System.err.printf("Options %s == %s and %s == %s are mutually exclusive.%n", Options.EXCLUDE_SESSION, options.isExcludeSession, 
						                                                                   Options.GENERATE_FIXT11_PACKAGE, options.isGenerateFixt11Package );
				System.exit(FAIL_STATUS);
			}
			generator.setExcludeSession(options.isExcludeSession);
			generator.setGenerateFixt11Package(options.isGenerateFixt11Package);
            generator.generate(inputStream, new File(options.outputDir));
		} catch (Exception e) {
			e.printStackTrace(System.err);
		}
	}
	
	@Command(name = "Options", mixinStandardHelpOptions = true, description = "Options for generation of QuickFIX/J Code from a FIX Orchestra Repository")
	static class Options {
		static final String GENERATE_FIXT11_PACKAGE = "--generateFixt11Package";
		static final String EXCLUDE_SESSION = "--excludeSession";

		@Option(names = { "-o", "--output-dir" }, defaultValue = "target/generated-sources", 
				paramLabel = "OUTPUT_DIRECTORY", description = "The output directory, Default : ${DEFAULT-VALUE}")
		String outputDir = "target/generated-sources";

		@Option(names = { "-i", "--orchestra-file" }, required = true, 
				paramLabel = "ORCHESTRA_FILE", description = "The path/name of the FIX OrchestraFile")
		String orchestraFileName;

		@Option(names = { "--disableBigDecimal" }, defaultValue = "false", fallbackValue = "true", 
				paramLabel = "DISABLE_BIG_DECIMAL", description = "Disable the use of Big Decimal for Decimal Fields, Default : ${DEFAULT-VALUE}")
		boolean isDisableBigDecimal = true;

		@Option(names = { "--generateMessageBaseClass" }, defaultValue = "false", fallbackValue = "true", 
				paramLabel = "GENERATE_MESSAGE_BASE_CLASS", description ="Generates Message Base Class, Standard Header & Trailer Default : ${DEFAULT-VALUE}")
		boolean isGenerateMessageBaseClass = false;

		@Option(names = { EXCLUDE_SESSION }, defaultValue = "false", fallbackValue = "true", 
				paramLabel = "EXCLUDE_SESSION", description ="Excludes Session Category Messages, Components and Groups exclusive to Session Layer and Fields used by Session Layer from the generated code, Default : ${DEFAULT-VALUE}")
		boolean isExcludeSession = false;

		@Option(names = { GENERATE_FIXT11_PACKAGE }, defaultValue = "true", fallbackValue = "true", 
				paramLabel = "GENERATE_FIXT_PACKAGE", description ="Generates FIXT11 Package : ${DEFAULT-VALUE}")
		boolean isGenerateFixt11Package = true;
	}

	private String decimalTypeString = DECIMAL_FIELD;

	private Repository repository;
	
	protected void initialise(InputStream inputFile) throws JAXBException {
		this.repository = unmarshal(inputFile);
	}
	
	public void generate(InputStream inputFile, File outputDir) {
		try {
			if (!this.isGenerateBigDecimal) {
				decimalTypeString = DOUBLE_FIELD;
			}
			Map<String, CodeSetType> codeSets = new HashMap<>();
			Map<Integer, ComponentType> components = new HashMap<>();
			Map<Integer, FieldType> fields = new HashMap<>();
			Map<Integer, GroupType> groups = new HashMap<>();
			List<MessageType> sessionMessages = new ArrayList<>();
			Set<BigInteger> sessionFieldIds = new HashSet<BigInteger>();
			Set<BigInteger> nonSessionFieldIds = new HashSet<BigInteger>();
			
			initialise(inputFile);
			final List<MessageType> messages = repository.getMessages().getMessage();
			final List<FieldType> fieldList = this.repository.getFields().getField();
			final List<ComponentType> componentList = repository.getComponents().getComponent();
			for (final FieldType fieldType : fieldList) {
				BigInteger id = fieldType.getId();
				fields.put(id.intValue(), fieldType);
			}
			for (final ComponentType component : componentList) {
				components.put(component.getId().intValue(), component);
			}
			List<GroupType> groupList = repository.getGroups().getGroup();
			for (final GroupType group : groupList) {
				groups.put(group.getId().intValue(), group);
			}
			sessionMessages = excludeSessionMessages(messages);
			collectFieldIds(messages, nonSessionFieldIds, groups, components, fields, this.isGenerateMessageBaseClass);
			//collect fields  ids from the session messages
			collectFieldIds(sessionMessages, sessionFieldIds, groups, components, fields, this.isGenerateMessageBaseClass);
			//restrict nonSessionFieldIds to fields removing fields that are used in session messages
			nonSessionFieldIds.removeAll(sessionFieldIds);
			
			final List<CodeSetType> codeSetList = repository.getCodeSets().getCodeSet();
			for (final CodeSetType codeSet : codeSetList) {
				codeSets.put(codeSet.getName(), codeSet);
			}

			final File fileDir = getPackagePath(outputDir, FIELD_PACKAGE);
			fileDir.mkdirs();
			for (final FieldType fieldType : fieldList) {
				BigInteger id = fieldType.getId();				
				if (!isExcludeSession || nonSessionFieldIds.contains(id)) {
					generateField(outputDir, fieldType, FIELD_PACKAGE, codeSets, this.isGenerateBigDecimal, this.decimalTypeString);
				}
			}

			String version = repository.getVersion();
			// Split off EP portion of version
			final String[] parts = version.split("_");
			if (parts.length > 0) {
				version = parts[0];
			}
			final String versionPath = version.replaceAll("[\\.]", "").toLowerCase();
			final String componentPackage = getPackage(QUICKFIX, versionPath, COMPONENT);
			final File componentDir = getPackagePath(outputDir, componentPackage);
			componentDir.mkdirs();
			if (isGenerateFixt11Package) {
				getPackagePath(outputDir, getPackage(QUICKFIX, FIXT11, COMPONENT)).mkdirs();
			}

			// shallow copy groups so that we can keep original while segregating session from non sesssion 
			Map<Integer, GroupType> nonSessionGroups = new HashMap<Integer, GroupType>(groups);  
			Map<Integer, GroupType> sessionGroups = excludeSessionGroups(nonSessionGroups);
			for (final GroupType group : nonSessionGroups.values()) {
				generateGroup(outputDir, group, componentPackage, nonSessionGroups, components, fields);
			}
			if (!isExcludeSession) { // process groups that are exclusive to session messages
				for (final GroupType group : sessionGroups.values()) {
					if (isGenerateFixt11Package) {
						generateGroup(outputDir, group, getPackage(QUICKFIX, FIXT11, COMPONENT), sessionGroups, components, fields);
					} else {
						generateGroup(outputDir, group, componentPackage, sessionGroups, components, fields);
					}
				}
			}
			for (final ComponentType component : componentList) {
				int id = component.getId().intValue();
				// if isGenerateMessageBaseClass excluded, standard header and trailer must be excluded
				if ((id != COMPONENT_ID_STANDARD_HEADER && id != COMPONENT_ID_STANDARD_TRAILER) || this.isGenerateMessageBaseClass) {
					generateComponent(outputDir, component, componentPackage, groups, components, fields);
				}
			}
			final String messagePackage = getPackage(QUICKFIX, versionPath);
			final File messageDir = getPackagePath(outputDir, messagePackage);
			messageDir.mkdirs();
			// generate non Session Messages 
			generateMessages(outputDir, messages, componentPackage, messagePackage, groups, components, fields);
			if (!isExcludeSession) {
				if (isGenerateFixt11Package) {
					generateMessages(outputDir, sessionMessages, getPackage(QUICKFIX, FIXT11, COMPONENT), getPackage(QUICKFIX, FIXT11), groups, components, fields);
				} else {
					generateMessages(outputDir, sessionMessages, componentPackage, messagePackage, groups, components, fields);
				}
			}
			if (this.isGenerateMessageBaseClass) {
				generateMessageBaseClass(outputDir, version, messagePackage);
			}
			generateMessageFactory(outputDir, messagePackage, messages, groups, fields);
			generateMessageCracker(outputDir, messagePackage, messages);

		} catch (JAXBException | IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Excludes Session GroupType
	 * @param groupList 
	 * @return the GroupType that have been excluded
	 */
	private static Map<Integer, GroupType> excludeSessionGroups(Map<Integer, GroupType> groups) {
		Map<Integer, GroupType> excludedGroups = new HashMap<Integer, GroupType>(); 
		for (final GroupType group : groups.values()) {
			if (GROUPS_EXCLUSIVE_TO_SESSION.contains(group.getId())) {
				excludedGroups.put(group.getId().intValue(), group);
			}
		}
		groups.keySet().removeAll(excludedGroups.keySet());
		return excludedGroups;
	}
	
	/**
	 * Excludes Session messages
	 * @param messageList 
	 * @return the MessageTypes that have been excluded
	 */
	private static List<MessageType> excludeSessionMessages(List<MessageType> messageList) {
		List<MessageType> excludedMessages = new ArrayList<MessageType>(); 
		for (final MessageType message : messageList) {
			if (message.getCategory().equals("Session")) {
				excludedMessages.add(message);
			}
		}
		messageList.removeAll(excludedMessages);
		return excludedMessages;
	}

	public boolean isGenerateMessageBaseClass() {
		return this.isGenerateMessageBaseClass;
	}

	public void setGenerateMessageBaseClass(boolean isGenerateMessageBaseClass) {
		this.isGenerateMessageBaseClass = isGenerateMessageBaseClass;
	}

	private static void generateMessages(File outputDir, 
			                             final List<MessageType> messages, 
			                             final String componentPackage,
			                             final String messagePackage, 
			                             Map<Integer, GroupType> groups, 
			                             Map<Integer, ComponentType> components, 
			                             Map<Integer, FieldType> fields) throws IOException {
		for (final MessageType message : messages) {
			generateMessage(outputDir, message, messagePackage, componentPackage, groups,  components, fields);
		}
	}
	
	private static void collectFieldIds(List<MessageType> messageList, Set<BigInteger> includedFieldIds, 
			                    Map<Integer, GroupType> groups, 
			                    Map<Integer, ComponentType> components, 
			                    Map<Integer, FieldType> fields, 
			                    boolean isGenerateMessageBaseClass) throws IOException {
		for (final MessageType messageType : messageList) {
			final List<Object> members = messageType.getStructure().getComponentRefOrGroupRefOrFieldRef();
			collectFieldIdsFromMembers(members, includedFieldIds, groups, components, fields, isGenerateMessageBaseClass);
		}
	}

	private static void collectFieldIdsFromMembers(List<Object> members, Set<BigInteger> includedFieldIds, 
			                                      Map<Integer, GroupType> groups, 
			                                      Map<Integer, ComponentType> components, 
			                                      Map<Integer, FieldType> fields,
			                                      boolean isGenerateMessageBaseClass) throws IOException {
		for (final Object member : members) {
			if (member instanceof FieldRefType) {
				final FieldRefType fieldRefType = (FieldRefType) member;
				includedFieldIds.add(fieldRefType.getId());
			} else if (member instanceof GroupRefType) {
				final int id = ((GroupRefType) member).getId().intValue();
				final GroupType groupType = groups.get(id);
				if (groupType != null) {
					final int numInGroupId = groupType.getNumInGroup().getId().intValue();
					final FieldType numInGroupField = fields.get(numInGroupId);
					includedFieldIds.add(numInGroupField.getId());
					collectFieldIdsFromMembers(groupType.getComponentRefOrGroupRefOrFieldRef(),includedFieldIds, groups, components, fields, isGenerateMessageBaseClass);
				} else {
					System.err.format("collectFieldIdsFromMembers : Group missing from repository; id=%d%n", id);
				}
			} else if (member instanceof ComponentRefType) {
				final int id = ((ComponentRefType) member).getId().intValue();
				final ComponentType componentType = components.get(id);
				if (null != componentType) {
					if ((id != COMPONENT_ID_STANDARD_HEADER && id != COMPONENT_ID_STANDARD_TRAILER) || isGenerateMessageBaseClass) {
						collectFieldIdsFromMembers(componentType.getComponentRefOrGroupRefOrFieldRef(),includedFieldIds, groups, components, fields, isGenerateMessageBaseClass);
					}
				} else {
					System.err.format("Component missing from repository; id=%d%n", id);
				}
			}
		}
	}

	private static void generateComponent(File outputDir, 
			              				  ComponentType componentType, 
			              				  String packageName, 
			              				  Map<Integer, GroupType> groups, 
			              				  Map<Integer, ComponentType> components, 
			              				  Map<Integer, FieldType> fields) throws IOException {
		final String name = toTitleCase(componentType.getName());
		final File file = getClassFilePath(outputDir, packageName, name);
		try (FileWriter writer = new FileWriter(file)) {
			writeFileHeader(writer);
			writePackage(writer, packageName);
			writeImport(writer, "quickfix.FieldNotFound");
			writeImport(writer, "quickfix.Group");

			writeClassDeclaration(writer, name, "quickfix.MessageComponent");
			writeSerializationVersion(writer, SERIALIZATION_VERSION);
			writeMsgType(writer, "");

			final List<Object> members = componentType.getComponentRefOrGroupRefOrFieldRef();
			final List<Integer> componentFields = members.stream().filter(member -> member instanceof FieldRefType)
					.map(member -> ((FieldRefType) member).getId().intValue()).collect(Collectors.toList());
			writeComponentFieldIds(writer, componentFields);

			final List<Integer> componentGroupFields = new ArrayList<>();
			writeGroupFieldIds(writer, componentGroupFields);
			writeComponentNoArgConstructor(writer, name);

			writeMemberAccessors(writer, members, packageName, packageName, groups, components, fields);

			writeEndClassDeclaration(writer);
		}
	}

	private static void generateField(File outputDir, 
									  FieldType fieldType, 
									  String packageName, 
									  Map<String, CodeSetType> codeSets, 
									  boolean isGenerateBigDecimal, 
									  String decimalTypeString) throws IOException {
		final String name = toTitleCase(fieldType.getName());
		final File file = getClassFilePath(outputDir, packageName, name);
		try (FileWriter writer = new FileWriter(file)) {
			writeFileHeader(writer);
			writePackage(writer, packageName);
			final String type = fieldType.getType();
			final CodeSetType codeSet = codeSets.get(type);
			final String fixType = codeSet == null ? type : codeSet.getType();

			if (DATE_TYPES.contains(fixType)) {
				writeImport(writer, "java.time.LocalDate");
				writeImport(writer, "java.time.LocalTime");
				writeImport(writer, "java.time.LocalDateTime");

			}
			final String baseClassname = getFieldBaseClass(fixType, decimalTypeString );
			if (baseClassname.equals(DECIMAL_FIELD) && isGenerateBigDecimal) {
					writeImport(writer, "java.math.BigDecimal"); 
			} //else the primitive double is used 
			final String qualifiedBaseClassname = getQualifiedClassName(QUICKFIX, baseClassname);
			writeImport(writer, qualifiedBaseClassname);
			writeClassDeclaration(writer, name, baseClassname);
			writeSerializationVersion(writer, SERIALIZATION_VERSION);
			final int fieldId = fieldType.getId().intValue();
			writeFieldId(writer, fieldId);
			if (codeSet != null) {
				writeValues(writer, codeSet);
			}
			writeFieldNoArgConstructor(writer, name, fieldId);
			writeFieldArgConstructor(writer, name, fieldId, baseClassname, isGenerateBigDecimal);
			writeEndClassDeclaration(writer);
		}
	}

	private static void generateGroup(File outputDir, 
			            	   GroupType groupType, 
			            	   String packageName, 
			            	   Map<Integer, GroupType> groups, 
			            	   Map<Integer, ComponentType> components, 
			            	   Map<Integer, FieldType> fields) throws IOException {
		final String name = toTitleCase(groupType.getName());
		final File file = getClassFilePath(outputDir, packageName, name);
		try (FileWriter writer = new FileWriter(file)) {
			writeFileHeader(writer);
			writePackage(writer, packageName);
			writeImport(writer, "quickfix.FieldNotFound");
			writeImport(writer, "quickfix.Group");

			writeClassDeclaration(writer, name, "quickfix.MessageComponent");
			writeSerializationVersion(writer, SERIALIZATION_VERSION);
			writeMsgType(writer, "");

			final List<Integer> componentFields = Collections.emptyList();
			writeComponentFieldIds(writer, componentFields);

			final int numInGroupId = groupType.getNumInGroup().getId().intValue();
			final List<Integer> componentGroupFields = new ArrayList<>();
			componentGroupFields.add(numInGroupId);
			writeGroupFieldIds(writer, componentGroupFields);

			writeComponentNoArgConstructor(writer, name);

			final FieldType numInGroupField = fields.get(numInGroupId);
			final String numInGroupFieldName = numInGroupField.getName();
			writeFieldAccessors(writer, numInGroupFieldName, numInGroupId);
			writeGroupInnerClass(writer, groupType, packageName, packageName, groups, components, fields);

			final List<Object> members = groupType.getComponentRefOrGroupRefOrFieldRef();
			writeMemberAccessors(writer, members, packageName, packageName, groups, components, fields);

			writeEndClassDeclaration(writer);
		}
	}

	private static void generateMessage(File outputDir, 
										MessageType messageType, 
										String messagePackage,
										String componentPackage, 
										Map<Integer, GroupType> groups,
										Map<Integer, ComponentType> components, 
										Map<Integer, FieldType> fields) throws IOException {
		String messageClassname = toTitleCase(messageType.getName());
		final String scenario = messageType.getScenario();
		if (!scenario.equals("base")) {
			messageClassname = messageClassname + toTitleCase(scenario);
		}
		final File file = getClassFilePath(outputDir, messagePackage, messageClassname);
		try (FileWriter writer = new FileWriter(file)) {
			writeFileHeader(writer);
			writePackage(writer, messagePackage);
			writeImport(writer, "quickfix.FieldNotFound");
			writeImport(writer, "quickfix.field.*");
			writeImport(writer, "quickfix.Group");

			writeClassDeclaration(writer, messageClassname, "Message");
			writeSerializationVersion(writer, SERIALIZATION_VERSION);
			writeMsgType(writer, messageType.getMsgType());

			final List<Object> members = messageType.getStructure().getComponentRefOrGroupRefOrFieldRef();
			writeMessageNoArgConstructor(writer, messageClassname);

			writeMemberAccessors(writer, members, messagePackage, componentPackage, groups, components, fields);

			writeEndClassDeclaration(writer);
		}
	}

	private static void generateMessageBaseClass(File outputDir, String version, String messagePackage) throws IOException {
		final File file = getClassFilePath(outputDir, messagePackage, "Message");
		try (FileWriter writer = new FileWriter(file)) {
			writeFileHeader(writer);
			writePackage(writer, messagePackage);
			writeImport(writer, "quickfix.field.*");
			writeClassDeclaration(writer, "Message", "quickfix.Message");
			writeSerializationVersion(writer, SERIALIZATION_VERSION);
			writeMessageNoArgBaseConstructor(writer, "Message");
			writeProtectedMessageBaseConstructor(writer, "Message", getBeginString(version));
			writeMessageDerivedHeaderClass(writer);

			writeEndClassDeclaration(writer);
		}
	}

	private static void generateMessageCracker(File outputDir, String messagePackage, List<MessageType> messageList)
			throws IOException {
		final File file = getClassFilePath(outputDir, messagePackage, "MessageCracker");
		try (FileWriter writer = new FileWriter(file)) {
			writeFileHeader(writer);
			writePackage(writer, messagePackage);
			writeImport(writer, "quickfix.*");
			writeImport(writer, "quickfix.field.*");
			writeClassDeclaration(writer, "MessageCracker");

			writer.write(String.format(
					"%n%spublic void onMessage(quickfix.Message message, SessionID sessionID) throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {%n",
					indent(1)));
			writer.write(String.format("%sthrow new UnsupportedMessageType();%n", indent(2)));
			writer.write(String.format("%s}%n", indent(1)));

			for (final MessageType messageType : messageList) {
				final String name = messageType.getName();
				final String scenario = messageType.getScenario();
				if (!scenario.equals("base")) {
					continue;
				}

				writer.write(String.format("%s/**%n", indent(1)));
				writer.write(String.format("%s * Callback for %s message.%n", indent(1), name));
				writer.write(String.format("%s * @param message%n", indent(1)));
				writer.write(String.format("%s * @param sessionID%n", indent(1)));
				writer.write(String.format("%s * @throws FieldNotFound%n", indent(1)));
				writer.write(String.format("%s * @throws UnsupportedMessageType%n", indent(1)));
				writer.write(String.format("%s * @throws IncorrectTagValue%n", indent(1)));
				writer.write(String.format("%s */%n", indent(1)));
				writer.write(String.format(
						"%n%spublic void onMessage(%s message, SessionID sessionID) throws FieldNotFound, UnsupportedMessageType, IncorrectTagValue {%n",
						indent(1), name));
				writer.write(String.format("%sthrow new UnsupportedMessageType();%n", indent(2)));
				writer.write(String.format("%s}%n", indent(1)));
			}

			final String crackMethodName = "crack" + messagePackage.split("\\.")[1];
			writer.write(
					String.format("%n%spublic void crack(quickfix.Message message, SessionID sessionID)%n", indent(1)));
			writer.write(
					String.format("%sthrows UnsupportedMessageType, FieldNotFound, IncorrectTagValue {%n", indent(2)));
			writer.write(String.format("%s%s((Message) message, sessionID);%n", indent(2), crackMethodName));
			writer.write(String.format("%s}%n", indent(1)));

			writer.write(String.format("%n%spublic void %s(Message message, SessionID sessionID)%n", indent(1),
					crackMethodName));
			writer.write(
					String.format("%sthrows UnsupportedMessageType, FieldNotFound, IncorrectTagValue {%n", indent(2)));
			writer.write(String.format("%sString type = message.getHeader().getString(MsgType.FIELD);%n", indent(2)));

			writer.write(String.format("%sswitch (type) {%n", indent(2)));
			for (final MessageType messageType : messageList) {
				final String name = messageType.getName();
				final String scenario = messageType.getScenario();
				if (!scenario.equals("base")) {
					continue;
				}
				writer.write(String.format("%scase %s.MSGTYPE:%n", indent(2), name));
				writer.write(
						String.format("%sonMessage((%s)message, sessionID);%n%sbreak;%n", indent(3), name, indent(3)));
			}

			writer.write(String.format("%sdefault:%n%sonMessage(message, sessionID);%n%s}%n%s}%n", indent(2), indent(3),
					indent(2), indent(1)));
			writeEndClassDeclaration(writer);
		}
	}

	private static void generateMessageFactory(File outputDir, 
			                           String messagePackage, 
			                           List<MessageType> messageList, 
			                           Map<Integer, GroupType> groups, 
			                           Map<Integer, FieldType> fields)
			throws IOException {
		final File file = getClassFilePath(outputDir, messagePackage, "MessageFactory");
		try (FileWriter writer = new FileWriter(file)) {
			writeFileHeader(writer);
			writePackage(writer, messagePackage);
			writeImport(writer, "quickfix.Message");
			writeImport(writer, "quickfix.Group");
			writer.write(
					String.format("%npublic class %s implements %s {%n", "MessageFactory", "quickfix.MessageFactory"));
			writeMessageCreateMethod(writer, messageList, messagePackage);
			writeGroupCreateMethod(writer, messageList, messagePackage, groups, fields);
			writeEndClassDeclaration(writer);
		}
	}

	private static String getBeginString(String version) {
		if (version.startsWith("FIX.5") || version.equals(FIX_LATEST)) {
			return FIXT_1_1;
		} else {
			return version;
		}
	}

	private static File getClassFilePath(File outputDir, String packageName, String className) {
		final StringBuilder sb = new StringBuilder();
		sb.append(packageName.replace('.', File.separatorChar));
		sb.append(File.separatorChar);
		sb.append(className);
		sb.append(".java");
		return new File(outputDir, sb.toString());
	}

	private static String getFieldBaseClass(String type, String decimalTypeString) {
		String baseType;
		switch (type) {
		case "char":
			baseType = "CharField";
			break;
		case "Price":
		case "Amt":
		case "Qty":
		case "PriceOffset":
			baseType = decimalTypeString;
			break;
		case "int":
		case "NumInGroup":
		case "SeqNum":
		case "Length":
		case "TagNum":
		case "DayOfMonth":
			baseType = "IntField";
			break;
		case "UTCTimestamp":
			baseType = "UtcTimeStampField";
			break;
		case "UTCTimeOnly":
		case "LocalMktTime":
			baseType = "UtcTimeOnlyField";
			break;
		case "UTCDateOnly":
		case "LocalMktDate":
			baseType = "UtcDateOnlyField";
			break;
		case "Boolean":
			baseType = "BooleanField";
			break;
		case "float":
		case "Percentage":
			baseType = DOUBLE_FIELD;
			break;
		default:
			baseType = "StringField";
		}
		return baseType;
	}

	private static void getGroupFields(List<Object> members, 
									   List<Integer> groupComponentFields, 
									   Map<Integer, GroupType> groups, 
									   Map<Integer, ComponentType> components) {
		for (final Object member : members) {
			if (member instanceof FieldRefType) {
				groupComponentFields.add(((FieldRefType) member).getId().intValue());
			} else if (member instanceof GroupRefType) {
				final int id = ((GroupRefType) member).getId().intValue();
				final GroupType groupType = groups.get(id);
				if (groupType != null) {
					groupComponentFields.add(groupType.getNumInGroup().getId().intValue());
				} else {
					System.err.format("getGroupFields : Group missing from repository; id=%d%n", id);
				}
			} else if (member instanceof ComponentRefType) {
				final ComponentType componentType = components.get(((ComponentRefType) member).getId().intValue());
				getGroupFields(componentType.getComponentRefOrGroupRefOrFieldRef(), groupComponentFields, groups, components);
			}
		}
	}

	private static String getPackage(String... parts) {
		return String.join(".", parts);
	}

	private static File getPackagePath(File outputDir, String packageName) {
		final StringBuilder sb = new StringBuilder();
		sb.append(packageName.replace('.', File.separatorChar));
		return new File(outputDir, sb.toString());
	}

	private static String getQualifiedClassName(String packageName, String className) {
		return String.format("%s.%s", packageName, className);
	}

	private static String indent(int level) {
		final char[] chars = new char[level * SPACES_PER_LEVEL];
		Arrays.fill(chars, ' ');
		return new String(chars);
	}

	// Capitalize first char and any after underscore or space. Leave other caps
	// as-is.
	private static String toTitleCase(String text) {
		final String[] parts = text.split("_ ");
		return Arrays.stream(parts).map(part -> part.substring(0, 1).toUpperCase() + part.substring(1))
				.collect(Collectors.joining());
	}

	private static Repository unmarshal(InputStream inputFile) throws JAXBException {
		final JAXBContext jaxbContext = JAXBContext.newInstance(Repository.class);
		final Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
		return (Repository) jaxbUnmarshaller.unmarshal(inputFile);
	}

	private static  Writer writeClassDeclaration(Writer writer, String name) throws IOException {
		writer.write(String.format("%npublic class %s {%n", name));
		return writer;
	}

	private static Writer writeClassDeclaration(Writer writer, String name, String baseClassname) throws IOException {
		writer.write(String.format("%npublic class %s extends %s {%n", name, baseClassname));
		return writer;
	}

	private static Writer writeComponentAccessors(Writer writer, String componentName, String packageName) throws IOException {
		// QFJ Message Base class has accessors for the standard Header and Trailer
		// components
		// so omit accessor on derived class
		if (!componentName.equals("StandardHeader") && !componentName.equals("StandardTrailer")) {
			final String className = getQualifiedClassName(packageName, componentName);
			writer.write(String.format("%n%spublic void set(%s component) {%n%ssetComponent(component);%n%s}%n",
					indent(1), className, indent(2), indent(1)));
			writer.write(String.format(
					"%n%spublic %s get(%s component) throws FieldNotFound {%n%sgetComponent(component);%n%sreturn component;%n%s}%n",
					indent(1), className, className, indent(2), indent(2), indent(1)));
			writer.write(String.format("%n%spublic %s get%s%s() throws FieldNotFound {%n%sreturn get(new %s());%n%s}%n",
					indent(1), className, componentName, "Component", indent(2), className, indent(1)));
		}
		return writer;
	}

	private static Writer writeComponentFieldIds(Writer writer, List<Integer> componentFields) throws IOException {
		writer.write(String.format("%Sprivate int[] componentFields = {", indent(1)));
		for (final Integer fieldId : componentFields) {
			writer.write(String.format("%d, ", fieldId));
		}
		writer.write(String.format("};%n"));
		writer.write(String.format("%sprotected int[] getFields() { return componentFields; }%n", indent(1)));
		return writer;
	}

	private static Writer writeComponentNoArgConstructor(Writer writer, String className) throws IOException {
		writer.write(String.format("%n%spublic %s() {%n%ssuper();%n%s}%n", indent(1), className, indent(2), indent(1)));
		return writer;
	}

	private static Writer writeEndClassDeclaration(Writer writer) throws IOException {
		writer.write("}\n");
		return writer;
	}

	private static Writer writeFieldAccessors(Writer writer, String name, int id) throws IOException {
		final String qualifiedClassName = getQualifiedClassName(FIELD_PACKAGE, name);

		writer.write(String.format("%n%spublic void set(%s value) {%n%ssetField(value);%n%s}%n", indent(1),
				qualifiedClassName, indent(2), indent(1)));
		writer.write(String.format(
				"%n%spublic %s get(%s value) throws FieldNotFound {%n%sgetField(value);%n%sreturn value;%n%s}%n",
				indent(1), qualifiedClassName, qualifiedClassName, indent(2), indent(2), indent(1)));
		writer.write(String.format("%n%spublic %s get%s() throws FieldNotFound {%n%sreturn get(new %s());%n%s}%n",
				indent(1), qualifiedClassName, name, indent(2), qualifiedClassName, indent(1)));
		writer.write(String.format("%n%spublic boolean isSet(%s field) {%n%sreturn isSetField(field);%n%s}%n",
				indent(1), qualifiedClassName, indent(2), indent(1)));
		writer.write(String.format("%n%spublic boolean isSet%s() {%n%sreturn isSetField(%d);%n%s}%n", indent(1), name,
				indent(2), id, indent(1)));
		return writer;
	}

	private static Writer writeFieldArgConstructor(Writer writer, 
			 									   String className, 
			 									   int fieldId, 
			 									   String baseClassname, 
			 									   boolean isGenerateBigDecimal)
			throws IOException {
		switch (baseClassname) {
		case "BooleanField":
			writer.write(String.format("%n%spublic %s(Boolean data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(1)));
			writer.write(String.format("%n%spublic %s(boolean data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(1)));
			break;
		case "BytesField":
			writer.write(String.format("%n%spublic %s(byte[] data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(1)));
			break;
		case "CharField":
			writer.write(String.format("%n%spublic %s(Character data) {%n%ssuper(%d, data);%n%s}%n", indent(1),
					className, indent(2), fieldId, indent(1)));
			writer.write(String.format("%n%spublic %s(char data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(1)));
			break;
		case "UtcDateOnlyField":
			writer.write(String.format("%n%spublic %s(LocalDate data) {%n%ssuper(%d, data);%n%s}%n", indent(1),
					className, indent(2), fieldId, indent(1)));
			// added for compatibility with existing QFJ tests
			writer.write(String.format("%n%spublic %s(String data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(1)));
			break;
		case "UtcTimeOnlyField":
			writer.write(String.format("%n%spublic %s(LocalTime data) {%n%ssuper(%d, data);%n%s}%n", indent(1),
					className, indent(2), fieldId, indent(1)));
			break;
		case "UtcTimeStampField":
			writer.write(String.format("%n%spublic %s(LocalDateTime data) {%n%ssuper(%d, data);%n%s}%n", indent(1),
					className, indent(2), fieldId, indent(1)));
			break;
		case DECIMAL_FIELD:
			if (isGenerateBigDecimal) {
				writer.write(String.format("%n%spublic %s(BigDecimal data) {%n%ssuper(%d, data);%n%s}%n", indent(1),
						className, indent(2), fieldId, indent(1)));
				writer.write(String.format("%n%spublic %s(double data) {%n%ssuper(%d, BigDecimal.valueOf(data));%n%s}%n",
						indent(1), className, indent(2), fieldId, indent(1)));
				break;
			}  // else drop through as DoubleField is extended
		case DOUBLE_FIELD:
			writer.write(String.format("%n%spublic %s(Double data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(2)));
			writer.write(String.format("%n%spublic %s(double data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(2)));
			break;
		case "IntField":
			writer.write(String.format("%n%spublic %s(Integer data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(1)));
			writer.write(String.format("%n%spublic %s(int data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(1)));
			break;
		default:
			writer.write(String.format("%n%spublic %s(String data) {%n%ssuper(%d, data);%n%s}%n", indent(1), className,
					indent(2), fieldId, indent(1)));
		}
		return writer;
	}

	private static Writer writeFieldId(Writer writer, int fieldId) throws IOException {
		writer.write(String.format("%n%spublic static final int FIELD = %d;%n", indent(1), fieldId));
		return writer;
	}

	private static Writer writeFieldNoArgConstructor(Writer writer, String className, int fieldId) throws IOException {
		writer.write(String.format("%n%spublic %s() {%n%ssuper(%d);%n%s}%n", indent(1), className, indent(2), fieldId,
				indent(1)));
		return writer;
	}

	private static Writer writeFileHeader(Writer writer) throws IOException {
		writer.write("/* Generated Java Source File */\n");
		return writer;
	}

	private static void writeGroupCreateCase(Writer writer, 
			                                 String parentQualifiedName, 
			                                 GroupType groupType,
			                                 Map<Integer, GroupType> groups, 
					                         Map<Integer, FieldType> fields)
			throws IOException {
		final FieldType numInGroupField = fields.get(groupType.getNumInGroup().getId().intValue());
		final String numInGroupFieldName = numInGroupField.getName();

		final String numInGroupFieldClassname = getQualifiedClassName(FIELD_PACKAGE, numInGroupFieldName);
		writer.write(String.format("%scase %s.FIELD:%n", indent(3), numInGroupFieldClassname));
		writer.write(String.format("%sreturn new %s.%s();%n", indent(4), parentQualifiedName, numInGroupFieldName));
		final List<Object> members = groupType.getComponentRefOrGroupRefOrFieldRef();
		for (final Object member : members) {
			if (member instanceof GroupRefType) {
				final int id = ((GroupRefType) member).getId().intValue();
				final GroupType nestedGroupType = groups.get(id);
				writeGroupCreateCase(writer, String.format("%s.%s", parentQualifiedName, numInGroupFieldName),
						nestedGroupType, groups, fields);

			}
		}
	}

	private static Writer writeGroupCreateMethod(Writer writer, 
												 List<MessageType> messageList, 
												 String messagePackage,
				                                 Map<Integer, GroupType> groups, 
						                         Map<Integer, FieldType> fields)
			throws IOException {
		writer.write(String.format(
				"%n%spublic Group create(String beginString, String msgType, int correspondingFieldID) {%n",
				indent(1)));
		writer.write(String.format("%sswitch (msgType) {%n", indent(2)));
		for (final MessageType messageType : messageList) {
			final String messageName = messageType.getName();
			final String scenario = messageType.getScenario();
			if (!scenario.equals("base")) {
				continue;
			}
			writer.write(String.format("%scase %s.%s.MSGTYPE:%n", indent(1), messagePackage, messageName));
			writer.write(String.format("%sswitch (correspondingFieldID) {%n", indent(2)));

			final List<Object> members = messageType.getStructure().getComponentRefOrGroupRefOrFieldRef();
			for (final Object member : members) {
				if (member instanceof GroupRefType) {
					final int id = ((GroupRefType) member).getId().intValue();
					final GroupType groupType = groups.get(id);
					if (groupType != null) {
						final String parentQualifiedName = getQualifiedClassName(messagePackage, messageName);
						writeGroupCreateCase(writer, parentQualifiedName, groupType, groups, fields);
					} else {
						System.err.format("writeGroupCreateMethod : Group missing from repository; id=%d%n", id);
					}

				}
			}
			writer.write(String.format("%s}%n%sbreak;%n", indent(2), indent(2)));
		}

		writer.write(String.format("%s}%n%sreturn null;%n%s}%n", indent(2), indent(2), indent(1)));

		return writer;
	}

	private static Writer writeGroupFieldIds(Writer writer, List<Integer> componentFields) throws IOException {
		writer.write(String.format("%Sprivate int[] componentGroups = {", indent(1)));
		for (final Integer fieldId : componentFields) {
			writer.write(String.format("%d, ", fieldId));
		}
		writer.write(String.format("};%n"));
		writer.write(String.format("%sprotected int[] getGroupFields() { return componentGroups; }%n", indent(1)));
		return writer;
	}

	private static void writeGroupInnerClass(FileWriter writer, 
											 GroupType groupType, 
											 String packageName,
											 String componentPackage, 
											 Map<Integer, GroupType> groups,
											 Map<Integer, ComponentType> components,
											 Map<Integer, FieldType> fields) throws IOException {
		final int numInGroupId = groupType.getNumInGroup().getId().intValue();
		final String numInGroupFieldName = fields.get(groupType.getNumInGroup().getId().intValue()).getName();

		writeStaticClassDeclaration(writer, numInGroupFieldName, "Group");
		writeSerializationVersion(writer, SERIALIZATION_VERSION);

		final List<Integer> groupComponentFields = new ArrayList<>();
		getGroupFields(groupType.getComponentRefOrGroupRefOrFieldRef(), groupComponentFields, groups, components);
		writeOrderFieldIds(writer, groupComponentFields);

		final Integer firstFieldId = groupComponentFields.get(0);
		writeGroupNoArgConstructor(writer, numInGroupFieldName, numInGroupId, firstFieldId);

		final List<Object> members = groupType.getComponentRefOrGroupRefOrFieldRef();
		writeMemberAccessors(writer, members, packageName, componentPackage, groups, components, fields);

		writeEndClassDeclaration(writer);
	}

	private static Writer writeGroupNoArgConstructor(Writer writer, String className, int numInGrpId, int firstFieldId)
			throws IOException {
		writer.write(String.format("%n%spublic %s() {%n%ssuper(%d, %d, ORDER);%n%s}%n", indent(1), className, indent(2),
				numInGrpId, firstFieldId, indent(1)));
		return writer;
	}

	private static Writer writeImport(Writer writer, String className) throws IOException {
		writer.write("import ");
		writer.write(className);
		writer.write(";\n");
		return writer;
	}

	private static void writeMemberAccessors(FileWriter writer, 
									  List<Object> members, 
									  String packageName,
									  String componentPackage, 
									  Map<Integer, GroupType> groups, 
									  Map<Integer, ComponentType> components, 
									  Map<Integer, FieldType> fields) throws IOException {
		for (final Object member : members) {
			if (member instanceof FieldRefType) {
				final FieldRefType fieldRefType = (FieldRefType) member;
				final FieldType field = fields.get(fieldRefType.getId().intValue());
				writeFieldAccessors(writer, field.getName(), fieldRefType.getId().intValue());
			} else if (member instanceof GroupRefType) {
				final int id = ((GroupRefType) member).getId().intValue();
				final GroupType groupType = groups.get(id);
				if (groupType != null) {
					writeComponentAccessors(writer, groupType.getName(), componentPackage);
					final int numInGroupId = groupType.getNumInGroup().getId().intValue();
					final FieldType numInGroupField = fields.get(numInGroupId);
					final String numInGroupName = numInGroupField.getName();
					writeFieldAccessors(writer, numInGroupName, numInGroupId);
					writeGroupInnerClass(writer, groupType, packageName, componentPackage, groups, components, fields);
				} else {
					System.err.format("writeMemberAccessors : Group missing from repository; id=%d%n", id);
				}
			} else if (member instanceof ComponentRefType) {
				final ComponentType componentType = components.get(((ComponentRefType) member).getId().intValue());
				writeComponentAccessors(writer, componentType.getName(), componentPackage);
				final List<Object> componentMembers = componentType.getComponentRefOrGroupRefOrFieldRef();
				// when recursing don't write out component accessors
				writeMemberAccessors(writer,
									componentMembers.stream()
										.filter(componentMember -> componentMember instanceof FieldRefType || componentMember instanceof ComponentRefType)
										.collect(Collectors.toList()),
									packageName, 
									componentPackage,
									groups,
									components,
									fields);
			}
		}
	}
	
	// In this method, only create messages with base scenario
	private static  Writer writeMessageCreateMethod(Writer writer, List<MessageType> messageList, String packageName)
			throws IOException {
		writer.write(String.format("%n%spublic Message create(String beginString, String msgType) {%n", indent(1)));
		writer.write(String.format("%sswitch (msgType) {%n", indent(2)));
		for (final MessageType messageType : messageList) {
			final String name = messageType.getName();
			final String scenario = messageType.getScenario();
			if (!scenario.equals("base")) {
				continue;
			}
			writer.write(String.format("%scase %s.%s.MSGTYPE:%n", indent(2), packageName, name));
			writer.write(String.format("%sreturn new %s();%n", indent(3), getQualifiedClassName(packageName, name)));
		}

		writer.write(String.format("%s}%n%sreturn new quickfix.fixlatest.Message();%n%s}%n", indent(2), indent(2),
				indent(1)));

		return writer;
	}

	private static Writer writeMessageDerivedHeaderClass(Writer writer) throws IOException {
		writeStaticClassDeclaration(writer, "Header", "quickfix.Message.Header");
		writeSerializationVersion(writer, SERIALIZATION_VERSION);
		writer.write(String.format("%n%spublic Header(Message msg) {%n%n%s}%n", indent(1), indent(1)));
		writeEndClassDeclaration(writer);
		return writer;
	}

	private static Writer writeMessageNoArgBaseConstructor(Writer writer, String className) throws IOException {
		writer.write(
				String.format("%n%spublic %s() {%n%sthis(null);%n%s}%n", indent(1), className, indent(2), indent(1)));
		return writer;
	}

	private static Writer writeMessageNoArgConstructor(Writer writer, String className) throws IOException {
		writer.write(String.format(
				"%n%spublic %s() {%n%ssuper();%n%sgetHeader().setField(new quickfix.field.MsgType(MSGTYPE));%n%s}%n",
				indent(1), className, indent(2), indent(2), indent(1)));
		return writer;
	}

	private static Writer writeMsgType(Writer writer, String msgType) throws IOException {
		writer.write(String.format("%n%spublic static final String MSGTYPE = \"%s\";%n", indent(1), msgType));
		return writer;
	}

	private static Writer writeOrderFieldIds(Writer writer, List<Integer> componentFields) throws IOException {
		writer.write(String.format("%Sprivate static final int[]  ORDER = {", indent(1)));
		for (final Integer fieldId : componentFields) {
			writer.write(String.format("%d, ", fieldId));
		}
		writer.write(String.format("0};%n"));
		return writer;
	}

	private static Writer writePackage(Writer writer, String packageName) throws IOException {
		writer.write("package ");
		writer.write(packageName);
		writer.write(";\n");
		return writer;
	}

	private static Writer writeProtectedMessageBaseConstructor(Writer writer, String className, String beginString)
			throws IOException {
		writer.write(String.format(
				"%sprotected %s(int[] fieldOrder) {%n%ssuper(fieldOrder);%n%sheader = new Header(this);%n%strailer = new Trailer();%n%sgetHeader().setField(new BeginString(\"%s\"));%n%s}%n",
				indent(1), className, indent(2), indent(2), indent(2), indent(2), beginString, indent(1)));
		return writer;
	}

	private static Writer writeSerializationVersion(Writer writer, long serializationVersion) throws IOException {
		writer.write(String.format("%sstatic final long serialVersionUID = %dL;%n", indent(1), serializationVersion));
		return writer;
	}

	private static Writer writeStaticClassDeclaration(Writer writer, String name, String baseClassname) throws IOException {
		writer.write(String.format("%npublic static class %s extends %s {%n", name, baseClassname));
		return writer;
	}

	private static Writer writeValues(Writer writer, CodeSetType codeSet) throws IOException {
		final String type = codeSet.getType();
		for (final CodeType code : codeSet.getCode()) {
			String name = CodeGeneratorTransformUtil.precedeCapsWithUnderscore(code.getName());
			switch (type) {
			case "Boolean":
				writer.write(String.format("%n%spublic static final boolean %s = %s;%n", indent(1), name,
						code.getValue().equals("Y")));
				break;
			case "char":
				writer.write(
						String.format("%n%spublic static final char %s = \'%s\';%n", indent(1), name, code.getValue()));
				break;
			case "int":
				writer.write(String.format("%n%spublic static final int %s = %s;%n", indent(1), name, code.getValue()));
				break;
			default:
				writer.write(String.format("%n%spublic static final String %s = \"%s\";%n", indent(1), name,
						code.getValue()));
			}

		}
		return writer;
	}

	public void setGenerateBigDecimal(boolean isGenerateBigDecimal) {
		this.isGenerateBigDecimal = isGenerateBigDecimal;
	}

	public boolean isExcludeSession() {
		return isExcludeSession;
	}

	public void setExcludeSession(boolean isExcludeSession) {
		if (isExcludeSession && this.isGenerateFixt11Package) {
			throw new IllegalArgumentException("ExcludeSession == true and GenerateFixt11Package == true are mutually exclusive.");
		}
		this.isExcludeSession = isExcludeSession;
	}

	public boolean isGenerateFixt11Package() {
		return isGenerateFixt11Package;
	}

	public void setGenerateFixt11Package(boolean isGenerateFixt11Package) {
		if (isGenerateFixt11Package && this.isExcludeSession) {
			throw new IllegalArgumentException("GenerateFixt11Package == true and ExcludeSession = true are mutually exclusive.");
		}
		this.isGenerateFixt11Package = isGenerateFixt11Package;
	}

}
