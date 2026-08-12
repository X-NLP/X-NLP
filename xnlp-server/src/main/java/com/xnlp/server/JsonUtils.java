package com.xnlp.server;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/***
 * @author zhengfan
 * @since 1.0
 */
public final class JsonUtils {

    /**
     * 反序列化时，对象结构不匹配则报错，需要结合try-catch使用，明确catch时的对象类型
     */
    public static final ObjectMapper JACKSON_OBJECT_MAPPER_ORDINARY = new ObjectMapper();
    public static final ObjectMapper JACKSON_OBJECT_MAPPER = new ObjectMapper();
    public static final ObjectMapper JACKSON_OBJECT_DATE_MAPPER = new ObjectMapper();

    public static final ObjectMapper JACKSON_OBJECT_MAPPER_NON_NULL = new ObjectMapper();

    public static final Map<Class<?>, Class<?>> PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP = new HashMap<>();
    public static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
            new TypeReference<Map<String, Object>>() {
            };
    public static final TypeReference<Map<String, String>> MAP_STRING_REFERENCE =
            new TypeReference<Map<String, String>>() {
            };
    public static final TypeReference<HashMap<String, Object>> HASH_MAP_TYPE_REFERENCE =
            new TypeReference<HashMap<String, Object>>() {
            };
    public static final TypeReference<LinkedHashMap<String, Object>> LINKED_HASH_MAP_TYPE_REFERENCE =
            new TypeReference<LinkedHashMap<String, Object>>() {
            };
    public static final TypeReference<List<String>> ARRAY_LIST_TYPE_REFERENCE =
            new TypeReference<List<String>>() {
            };
    public static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE_REFERENCE =
            new TypeReference<List<Map<String, Object>>>() {
            };
    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);

    /**
     *
     Primitive Type Size Minimum Value Maximum Value Wrapper Type char 16-bit
     * Unicode 0 Unicode 216-1 Character byte 8-bit -128 +127 Byte short 16-bit
     * -215 (-32,768) +215-1 (32,767) Short int 32-bit -231 (-2,147,483,648)
     * +231-1 (2,147,483,647) Integer long 64-bit -263
     * (-9,223,372,036,854,775,808) +263-1 (9,223,372,036,854,775,807) Long
     * float 32-bit 32-bit IEEE 754 floating-point numbers Float double 64-bit
     * 64-bit IEEE 754 floating-point numbers Double boolean 1-bit true or false
     * Boolean void ----- ----- ----- Void
     */
    static {
        // 设置JSON时间格式
        SimpleDateFormat myDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP.put(char.class, Character.class);
        PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP.put(byte.class, Byte.class);
        PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP.put(short.class, Short.class);
        PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP.put(int.class, Integer.class);
        PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP.put(long.class, Long.class);
        PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP.put(float.class, Float.class);
        PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP.put(double.class, Double.class);
        PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP.put(boolean.class, Boolean.class);
        PRIMITIVE_TYPE_TO_WRAPPER_TYPE_MAP.put(void.class, Void.class);
        JACKSON_OBJECT_MAPPER.setSerializationInclusion(Include.ALWAYS);
        JACKSON_OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 允许json中包含非引号控制字符
        JACKSON_OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
        JACKSON_OBJECT_MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        JACKSON_OBJECT_MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        JACKSON_OBJECT_MAPPER.setDateFormat(myDateFormat);
        JavaTimeModule module = new JavaTimeModule();
        LocalDateTimeDeserializer dateTimeDeserializer = new LocalDateTimeDeserializer(DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss"));
        LocalDateTimeSerializer dateTimeSerializer = new LocalDateTimeSerializer(DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss"));
        module.addDeserializer(LocalDateTime.class, dateTimeDeserializer);
        module.addSerializer(LocalDateTime.class, dateTimeSerializer);
        JACKSON_OBJECT_MAPPER.registerModule(module);
        JACKSON_OBJECT_MAPPER_ORDINARY.registerModule(module);
        JACKSON_OBJECT_MAPPER_NON_NULL.setSerializationInclusion(Include.NON_NULL);
        JACKSON_OBJECT_MAPPER_NON_NULL.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 允许json中包含非引号控制字符
        JACKSON_OBJECT_MAPPER_NON_NULL.configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
        JACKSON_OBJECT_MAPPER_NON_NULL.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        JACKSON_OBJECT_MAPPER_NON_NULL.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        JACKSON_OBJECT_MAPPER_NON_NULL.setDateFormat(myDateFormat);

        JACKSON_OBJECT_DATE_MAPPER.setSerializationInclusion(Include.ALWAYS);
        JACKSON_OBJECT_DATE_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 允许json中包含非引号控制字符
        JACKSON_OBJECT_DATE_MAPPER.configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
        JACKSON_OBJECT_DATE_MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        JACKSON_OBJECT_DATE_MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        JACKSON_OBJECT_DATE_MAPPER.setDateFormat(myDateFormat);
    }

    public static boolean isJsonValid(String jsonString) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.readTree(jsonString);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 判断字符串是否为 JSON 数组
     *
     * @param jsonString
     * @return
     */
    public static boolean isJsonArrayValid(String jsonString) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            JsonNode rootNode = objectMapper.readTree(jsonString);
            // 判断解析后的 JsonNode 是否为数组
            return rootNode.isArray();
        } catch (Exception e) {
            log.warn(" JsonUtils isJsonArrayValid字符串非法", e);
            // 解析失败时返回 false
            return false;
        }
    }

    public static String toJson(Object obj) throws IOException {
        return JACKSON_OBJECT_MAPPER.writeValueAsString(obj);
    }

    public static String toJsonUnchecked(Object obj, String defaultReturn) {
        try {
            return JACKSON_OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("error json format ", e);
            return defaultReturn;
        }
    }

    public static String toJsonUnchecked(Object obj) {
        try {
            return JACKSON_OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("error json format ", e);
            return null;
        }
    }

    public static String toJsonNonNull(Object obj) throws IOException {
        return JACKSON_OBJECT_MAPPER_NON_NULL.writeValueAsString(obj);
    }

    public static String toJsonNonNullUnchecked(Object obj, String defaultReturn) {
        try {
            return JACKSON_OBJECT_MAPPER_NON_NULL.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("error json format ", e);
            return defaultReturn;
        }
    }

    public static String toJsonNonNullUnchecked(Object obj) {
        try {
            return JACKSON_OBJECT_MAPPER_NON_NULL.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("error json format ", e);
            return null;
        }
    }

    public static JsonNode toJsonNodeUnchecked(String json) {
        try {
            return JACKSON_OBJECT_MAPPER.readValue(json, JsonNode.class);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        return MissingNode.getInstance();
    }

    public static JsonNode toJsonNode(String json) throws IOException {
        return JACKSON_OBJECT_MAPPER.readValue(json, JsonNode.class);
    }

    public static <T> T toObject(String content, Class<T> classz) throws IOException {
        return JACKSON_OBJECT_MAPPER.readValue(content, classz);
    }

    public static <T> T toObjectForDate(String content, Class<T> classz) throws IOException {
        return JACKSON_OBJECT_DATE_MAPPER.readValue(content, classz);
    }

    public static <T> T toObjectWithoutException(String content, Class<T> classz) {
        try {
            if (Objects.isNull(content)) {
                return null;
            }
            return JACKSON_OBJECT_MAPPER.readValue(content, classz);
        } catch (IOException e) {
            log.warn("str to obj fail: {}", content, e);
        }
        return null;
    }

    public static <T> T toObject(String json, TypeReference<T> valueTypeRef) {
        if (null != json) {
            try {
                return toObjectThrowException(json, valueTypeRef);
            } catch (IOException e) {
                log.error(e.getMessage(), e);
            }
        }
        return null;
    }

    public static <T> T toObjectThrowException(String json, TypeReference<T> valueTypeRef) throws IOException {
        return (T) JACKSON_OBJECT_MAPPER.readValue(json, valueTypeRef);
    }

    public static Map<String, Object> toMap(String json) throws IOException {
        if (null != json) {
            return JACKSON_OBJECT_MAPPER.readValue(json, HASH_MAP_TYPE_REFERENCE);
        }
        return null;
    }

    public static Map<String, Object> toMapWithoutException(String jsonStr) {
        Map<String, Object> result = null;
        try {
            if (Objects.nonNull(jsonStr)) {
                result = JsonUtils.toMap(jsonStr);
            }
        } catch (IOException e) {
            log.warn("str to map fail: {}", jsonStr, e);
        }
        return result == null ? Collections.emptyMap() : result;
    }

    public static <K, V> Map<K, V> toMapWithoutException(String json, TypeReference tf) {
        Map<K, V> result = null;
        try {
            if (Objects.nonNull(json)) {
                result = JsonUtils.toMap(json, tf);
            }
        } catch (IOException e) {
            log.warn("str to map fail, {}", e);
        }
        return result == null ? Collections.emptyMap() : result;
    }

    public static String toJsonWithOutException(Object object) {
        try {
            if (Objects.isNull(object)) {
                return StringUtils.EMPTY;
            }
            return toJson(object);
        } catch (IOException e) {
            log.warn("obj to str fail: {}", object, e);
        }
        return StringUtils.EMPTY;
    }

    public static String toJsonForDateWithOutException(Object object) {
        try {
            if (Objects.isNull(object)) {
                return StringUtils.EMPTY;
            }
            return JACKSON_OBJECT_DATE_MAPPER.writeValueAsString(object);
        } catch (IOException e) {
            log.warn("obj to str fail: {}", object, e);
        }
        return StringUtils.EMPTY;
    }

    public static <T> List<T> toList(String json, Class<? extends List> collectionClass, Class<T> elementClass)
            throws IOException {
        JavaType javaType = JACKSON_OBJECT_MAPPER.getTypeFactory().constructCollectionType(collectionClass,
                elementClass);
        return JACKSON_OBJECT_MAPPER.readValue(json, javaType);
    }

    public static <T> List<T> toListUnchecked(String json, Class<? extends List> collectionClass,
                                              Class<T> elementClass) {
        JavaType javaType = JACKSON_OBJECT_MAPPER.getTypeFactory().constructCollectionType(collectionClass,
                elementClass);
        try {
            return JACKSON_OBJECT_MAPPER.readValue(json, javaType);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    public static Map<String, Object> toMap(JsonNode jsonNode) {
        if (null != jsonNode) {
            return JACKSON_OBJECT_MAPPER.convertValue(jsonNode, MAP_TYPE_REFERENCE);
        }
        return null;
    }
    // 因proguard混淆后,下面的泛型不能正常工作, 故注释掉, 改为上面的方法
    //    public static <K, V> Map<K, V> toMap(String json) throws IOException {
    //        if (null != json) {
    //            return JACKSON_OBJECT_MAPPER.readValue(json, new TypeReference<Map<K, V>>() {
    //            });
    //        }
    //        return null;
    //    }

    public static <K, V> Map<K, V> toMap(String json, TypeReference tf) throws IOException {
        if (null != json) {
            return (Map<K, V>) JACKSON_OBJECT_MAPPER.readValue(json, tf);
        }
        return null;
    }

    public static ObjectNode createNode(String key, Object v) {
        return setNodeValue(JsonNodeFactory.instance.objectNode(), key, v);
    }

    public static ObjectNode createNode() {
        return JsonNodeFactory.instance.objectNode();
    }

    /***
     *
     * example:
     * addArrNodeValue(root,"a.b", node1 => {K,v})
     *
     * before
     *
     * { "a":{"b":[{"a":"b"}]}}
     *
     * after
     *
     * { "a":{"b":[{"a":"b"},{"K":"v"}]}}
     *
     */
    public static ObjectNode addArrNodeValue(ObjectNode root, String path, ObjectNode... nodes) {
        Validate.notEmpty(path);
        String[] arr = path.split("\\.");
        int counter = 0;
        ObjectNode node = null;
        for (String k : arr) {
            counter++;
            if (node == null) {
                node = root;
            }

            if (node.path(k).isMissingNode() && counter == arr.length) {
                node.set(k, JsonNodeFactory.instance.arrayNode());
            } else if (node.path(k).isMissingNode()) {
                node.set(k, JsonNodeFactory.instance.objectNode());
            }

            if (counter != arr.length) {
                node = (ObjectNode) node.path(k);
                continue;
            }

            ArrayNode arrValue = newArrayNode();
            if (node.path(k).isArray()) {

                for (JsonNode tmp : node.path(k)) {
                    arrValue.add(tmp);
                }

                for (ObjectNode nodeValue : nodes) {
                    arrValue.add(nodeValue);
                }

                node.set(k, arrValue);
            } else {
                throw new IllegalArgumentException("can't overwrite value " + path + ", cause is not empty");
            }

        }

        return root;
    }

    /***
     *
     * example:
     * setArrNodeValue(root,"a.b", node1 => {K,v})
     *
     * before
     *
     * {}
     *
     * after
     *
     * { "a":{"b":[{"a":"b"},{"K":"v"}]}}
     *
     * @return root
     *
     */
    public static ObjectNode setArrNodeValue(ObjectNode root, String path, ObjectNode... nodes) {
        Validate.notEmpty(path);
        String[] arr = path.split("\\.");
        int counter = 0;
        ObjectNode node = null;
        for (String k : arr) {
            counter++;
            if (node == null) {
                node = root;
            }
            if (node.path(k).isMissingNode()) {
                node.set(k, JsonNodeFactory.instance.objectNode());
            }

            if (counter != arr.length) {
                node = (ObjectNode) node.path(k);
                continue;
            }

            ArrayNode arrValue = newArrayNode();
            for (ObjectNode nodeValue : nodes) {
                arrValue.add(nodeValue);
            }

            node.set(k, arrValue);
        }

        return root;
    }

    /***
     *
     * example:
     * setNodeValue(root,"a.b", node1 => {K,v})
     *
     * before
     *
     * {}
     *
     * after
     *
     * { "a":{"b":{"a":"b"}}}
     *
     *
     * example:
     * setNodeValue(root,"a.b", 1)
     *
     * before
     *
     * {}
     *
     * after
     *
     * { "a":{"b":1}
     * @return root
     *
     */
    public static ObjectNode setNodeValue(ObjectNode root, String key, Object v) {
        Validate.notEmpty(key);
        final String[] arr = key.split("\\.");
        int counter = 0;
        ObjectNode node = null;
        for (String k : arr) {
            counter++;
            if (node == null) {
                node = root;
            }
            if (node.path(k).isMissingNode()) {
                node.set(k, JsonNodeFactory.instance.objectNode());
            }

            if (counter != arr.length) {
                node = (ObjectNode) node.path(k);
                continue;
            }

            if (v == null) {
                node.set(k, null);
                continue;
            }

            if (v instanceof ObjectNode) {
                node.set(k, (ObjectNode) v);
                continue;
            }

            if (v instanceof ArrayNode) {
                node.set(k, (ArrayNode) v);
                continue;
            }

            if (v instanceof String) {
                node.put(k, v.toString());
                continue;
            }

            if (v instanceof Integer) {
                node.put(k, (Integer) v);
                continue;
            }

            if (v instanceof Long) {
                node.put(k, (Long) v);
                continue;
            }

            if (v instanceof Float) {
                node.put(k, (Float) v);
                continue;
            }

            if (v instanceof Boolean) {
                node.put(k, (Boolean) v);
                continue;
            }

            if (v instanceof Double) {
                node.put(k, (Double) v);
                continue;
            }

            node.putPOJO(k, v);

        }
        return root;
    }

    public static ArrayNode newArrayNode() {
        return JsonNodeFactory.instance.arrayNode();
    }

    public static JsonNode getNode(JsonNode root, String key) {
        String[] snodes = key.split("\\.");
        for (String snode : snodes) {
            root = root.path(snode);
        }
        return root;
    }

    public static JsonNode toJsonNodeWithException(String json) throws IOException {
        JsonNode jsonNode;

        jsonNode = JACKSON_OBJECT_MAPPER.readValue(json, JsonNode.class);
        return jsonNode;
    }

    public static Map<String, Object> objToMap(Object obj) {
        Map<String, Object> map;
        try {
            String obj2Str = toJson(obj);
            map = toMap(obj2Str);
        } catch (IOException e) {
            map = new HashMap<>();
        }
        return map;
    }

    public static <T> T mapToObj(Map<String, Object> map, Class<T> classz) {
        try {
            return JACKSON_OBJECT_MAPPER.readValue(toJson(map), classz);
        } catch (IOException e) {
            log.warn("map to obj error: {}", map, e);
        }
        return null;
    }

    public static Map<String, Object> readMapFromFileUrl(URL fileUrl) throws IOException {
        return JACKSON_OBJECT_MAPPER.readValue(fileUrl, HASH_MAP_TYPE_REFERENCE);
    }
}
