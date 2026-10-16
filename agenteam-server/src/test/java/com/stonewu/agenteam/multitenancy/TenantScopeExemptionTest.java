package com.stonewu.agenteam.multitenancy;

import com.stonewu.agenteam.configuration.multitenancy.TenantScopeExemption;
import com.stonewu.agenteam.configuration.multitenancy.TenantScopeExemptions;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-F 机器校验：动态算出「不带租户条件」的数据访问集合，并断言其精确等于
 * {@link TenantScopeExemptions} 登记的集合。新增/删除任一条无租户条件查询都会失败。
 */
class TenantScopeExemptionTest {

    private static final Pattern XML_SCOPE = Pattern.compile(
            "(?i)\\bscope\\s*(=|!=|<>|<=|>=|<|>|in\\s*\\()");
    private static final Pattern JAVA_TENANT = Pattern.compile(
            "(?i)(enterprise_id|enterpriseId|EnterpriseId|getEnterpriseId|\\bscope\\b)");
    private static final Pattern WRAP_NEW = Pattern.compile(
            "new\\s+(QueryWrapper|UpdateWrapper|LambdaQueryWrapper|LambdaUpdateWrapper)\\s*<");
    private static final Pattern WRAP_W = Pattern.compile(
            "Wrappers\\.(<[^>]*>)?(query|update|lambdaQuery|lambdaUpdate)\\s*\\(");
    private static final Pattern WRAP_TYPE = Pattern.compile(
            "\\b(QueryWrapper|UpdateWrapper|LambdaQueryWrapper|LambdaUpdateWrapper)\\b");
    private static final Pattern MAPPER_CALL = Pattern.compile(
            "\\.(selectList|selectOne|selectCount|selectMaps|selectObjs|selectPage|selectByMap|"
                    + "selectBatchIds|selectById|selectByIds|update|updateById|delete|deleteById|"
                    + "deleteByMap|deleteBatchIds|insert|insertBatchSomeColumn|insertOrUpdate|list|"
                    + "page|count|getOne|save|saveOrUpdate|remove|removeById|removeByIds|removeByMap)\\s*\\(");

    private final File moduleRoot;

    TenantScopeExemptionTest() {
        this.moduleRoot = findModuleRoot();
    }

    @Test
    void xmlExemptionsMatchRegistry() {
        Set<String> actual = scanXmlNoTenant();
        Set<String> expected = TenantScopeExemptions.xmlExemptions().keySet();
        assertEqualSets("XML 映射语句", expected, actual);
    }

    @Test
    void javaExemptionsMatchRegistry() {
        Set<String> actual = scanJavaNoTenant();
        Set<String> expected = TenantScopeExemptions.javaExemptions().keySet();
        assertEqualSets("Java Wrapper 方法", expected, actual);
    }

    @Test
    void everyExemptionCarriesRationale() {
        for (Map.Entry<String, TenantScopeExemption> e : TenantScopeExemptions.xmlExemptions().entrySet()) {
            assertTrue(e.getValue() != null, "XML 豁免缺少理由: " + e.getKey());
        }
        for (Map.Entry<String, TenantScopeExemption> e : TenantScopeExemptions.javaExemptions().entrySet()) {
            assertTrue(e.getValue() != null, "Java 豁免缺少理由: " + e.getKey());
        }
    }

    // ------------------------------------------------------------------ XML

    private Set<String> scanXmlNoTenant() {
        Set<String> result = new HashSet<>();
        File mapperDir = new File(moduleRoot, "src/main/resources/mapper");
        if (!mapperDir.isDirectory()) {
            throw new IllegalStateException("找不到 mapper 目录: " + mapperDir);
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            // MyBatis 映射文件含 DOCTYPE（指向 mybatis.org 的 DTD），关闭外部 DTD 加载避免联网；
            // 个别 JDK 内置 Xerces 不支持某些 feature，逐条 try 以兼容。
            trySet(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder db = dbf.newDocumentBuilder();

            Map<String, Element> fragLocal = new LinkedHashMap<>();
            Map<String, Element> fragNs = new LinkedHashMap<>();
            List<File> xmlFiles = new ArrayList<>();
            collectXml(mapperDir, xmlFiles);
            // 两遍：先收集所有 <sql> 片段，再扫描语句
            for (File f : xmlFiles) {
                Document doc = db.parse(f);
                Element root = doc.getDocumentElement();
                String ns = root.getAttribute("namespace");
                for (Element sql : elementsByLocalName(root, "sql")) {
                    String id = sql.getAttribute("id");
                    if (id != null && !id.isEmpty()) {
                        fragLocal.put(id, sql);
                        if (ns != null && !ns.isEmpty()) {
                            fragNs.put(ns + "." + id, sql);
                        }
                    }
                }
            }
            for (File f : xmlFiles) {
                Document doc = db.parse(f);
                Element root = doc.getDocumentElement();
                String ns = root.getAttribute("namespace");
                for (String tag : List.of("select", "insert", "update", "delete")) {
                    for (Element stmt : elementsByLocalName(root, tag)) {
                        String id = stmt.getAttribute("id");
                        if (id == null || id.isEmpty()) {
                            continue;
                        }
                        String inlined = inlineIncludes(stmt, fragLocal, fragNs);
                        if (!hasTenantCondition(inlined)) {
                            result.add(ns + "#" + id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("XML 扫描失败", e);
        }
        return result;
    }

    private static void trySet(DocumentBuilderFactory dbf, String name, boolean value) {
        try {
            dbf.setFeature(name, value);
        } catch (Exception ignored) {
            // 某些 JDK 内置 Xerces 不支持该 feature，忽略即可
        }
    }

    private void collectXml(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                collectXml(c, out);
            } else if (c.getName().endsWith(".xml")) {
                out.add(c);
            }
        }
    }

    private List<Element> elementsByLocalName(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nl = root.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                String name = n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
                if (localName.equals(name)) {
                    out.add((Element) n);
                }
            }
        }
        return out;
    }

    private String inlineIncludes(Element node, Map<String, Element> fragLocal, Map<String, Element> fragNs) {
        StringBuilder sb = new StringBuilder();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.TEXT_NODE) {
                sb.append(c.getNodeValue());
            } else if (c.getNodeType() == Node.ELEMENT_NODE) {
                String name = c.getLocalName() != null ? c.getLocalName() : c.getNodeName();
                if ("include".equals(name)) {
                    String refid = ((Element) c).getAttribute("refid");
                    Element frag = fragLocal.get(refid);
                    if (frag == null) {
                        frag = fragNs.get(refid);
                    }
                    if (frag != null) {
                        sb.append(inlineIncludes(frag, fragLocal, fragNs));
                    }
                } else {
                    sb.append(inlineIncludes((Element) c, fragLocal, fragNs));
                }
            }
        }
        return sb.toString();
    }

    private boolean hasTenantCondition(String sql) {
        if (sql.contains("enterprise_id")) {
            return true;
        }
        return XML_SCOPE.matcher(sql).find();
    }

    // ------------------------------------------------------------------ Java

    private Set<String> scanJavaNoTenant() {
        Set<String> result = new HashSet<>();
        File javaDir = new File(moduleRoot, "src/main/java");
        if (!javaDir.isDirectory()) {
            throw new IllegalStateException("找不到 java 目录: " + javaDir);
        }
        List<File> javaFiles = new ArrayList<>();
        collectJava(javaDir, javaFiles);
        for (File f : javaFiles) {
            try {
                String src = Files.readString(f.toPath());
                String clean = stripCommentsAndLiterals(src);
                String base = f.getName().replace(".java", "");
                for (MethodBlock mb : extractMethods(src, clean, base)) {
                    String key = mb.className + "#" + mb.name;
                    String body = mb.body;
                    boolean usesWrapper = WRAP_NEW.matcher(body).find()
                            || WRAP_W.matcher(body).find()
                            || (WRAP_TYPE.matcher(body).find() && MAPPER_CALL.matcher(body).find());
                    if (!usesWrapper) {
                        continue;
                    }
                    boolean hasTenant = JAVA_TENANT.matcher(body).find();
                    if (!hasTenant) {
                        result.add(key);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Java 扫描失败: " + f, e);
            }
        }
        return result;
    }

    private void collectJava(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                collectJava(c, out);
            } else if (c.getName().endsWith(".java")) {
                out.add(c);
            }
        }
    }

    /**
     * 用等长的空格替换注释与字符串/字符字面量，使花括号恢复结构性，
     * 同时保持与原文等长的偏移量（clean.length() == src.length()），
     * 避免字面量里的 { } 干扰方法边界判定，也保证用 clean 的偏移去切 src 不会错位。
     */
    private String stripCommentsAndLiterals(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int j = i + 2;
                while (j < n && src.charAt(j) != '\n') {
                    j++;
                }
                appendSpaces(out, j - i);
                i = j;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j + 1 < n && !(src.charAt(j) == '*' && src.charAt(j + 1) == '/')) {
                    j++;
                }
                j = Math.min(n, j + 2);
                appendSpaces(out, j - i);
                i = j;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                int j = i + 1;
                while (j < n) {
                    if (src.charAt(j) == '\\') {
                        j += 2;
                    } else if (src.charAt(j) == quote) {
                        j++;
                        break;
                    } else {
                        j++;
                    }
                }
                appendSpaces(out, j - i);
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void appendSpaces(StringBuilder out, int count) {
        for (int k = 0; k < count; k++) {
            out.append(' ');
        }
    }

    private static final Set<String> NON_METHOD_BEFORE_PAREN = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "return",
            "new", "throw", "do", "else", "case", "default");

    private List<MethodBlock> extractMethods(String src, String clean, String className) {
        List<MethodBlock> blocks = new ArrayList<>();
        int n = clean.length();
        int depth = 0;
        int[] openAt = new int[n + 2];
        for (int i = 0; i < n; i++) {
            char c = clean.charAt(i);
            if (c == '{') {
                depth++;
                openAt[depth] = i;
            } else if (c == '}') {
                int start = openAt[depth];
                String before = clean.substring(0, start);
                String name = methodNameBefore(before);
                if (name != null) {
                    blocks.add(new MethodBlock(className, name, src.substring(start, i + 1)));
                }
                depth--;
            }
        }
        return blocks;
    }

    private String methodNameBefore(String before) {
        int e = before.length();
        while (e > 0 && Character.isWhitespace(before.charAt(e - 1))) {
            e--;
        }
        // 方法体前的结构必为 `) [throws ...]`；否则是类/代码块，不是方法
        if (e == 0 || before.charAt(e - 1) != ')') {
            return null;
        }
        int depth = 0;
        int open = -1;
        for (int k = e - 1; k >= 0; k--) {
            char ch = before.charAt(k);
            if (ch == ')') {
                depth++;
            } else if (ch == '(') {
                depth--;
                if (depth == 0) {
                    open = k;
                    break;
                }
            }
        }
        if (open < 0) {
            return null;
        }
        int j = open - 1;
        while (j >= 0 && Character.isWhitespace(before.charAt(j))) {
            j--;
        }
        int end = j + 1;
        while (j >= 0 && (Character.isJavaIdentifierPart(before.charAt(j)) || before.charAt(j) == '.')) {
            j--;
        }
        String token = before.substring(j + 1, end);
        if (token.isEmpty()) {
            return null;
        }
        // 排除 new X( 构造/匿名类、以及 class/interface/enum 声明
        int nb = j;
        while (nb >= 0 && Character.isWhitespace(before.charAt(nb))) {
            nb--;
        }
        if (nb >= 2 && before.regionMatches(nb - 2, "new", 0, 3)) {
            return null;
        }
        if (NON_METHOD_BEFORE_PAREN.contains(token)
                || token.equals("class") || token.equals("interface") || token.equals("enum")) {
            return null;
        }
        int dot = token.lastIndexOf('.');
        if (dot >= 0) {
            token = token.substring(dot + 1);
        }
        return token.isEmpty() ? null : token;
    }

    private record MethodBlock(String className, String name, String body) {
    }

    // ------------------------------------------------------------------ 断言

    private void assertEqualSets(String label, Set<String> expected, Set<String> actual) {
        if (expected.equals(actual)) {
            return;
        }
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new HashSet<>(actual);
        extra.removeAll(expected);
        StringBuilder msg = new StringBuilder();
        msg.append(label).append(" 豁免集合与登记表不一致。\n");
        msg.append("期望数量=").append(expected.size()).append("，实际数量=").append(actual.size()).append("。\n");
        if (!missing.isEmpty()) {
            msg.append("登记表中有、但代码里已不再是无租户条件（可能已加租户条件或方法被删除）——需从登记表移除: ")
                    .append(sorted(missing)).append("\n");
        }
        if (!extra.isEmpty()) {
            msg.append("代码里新增了无租户条件的项、但未登记——必须在 TenantScopeExemptions 显式登记并说明理由: ")
                    .append(sorted(extra)).append("\n");
        }
        assertEquals(expected, actual, msg.toString());
    }

    private List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        list.sort(null);
        return list;
    }

    private static File findModuleRoot() {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 5 && dir != null; i++) {
            if (new File(dir, "src/main/java").isDirectory()
                    && new File(dir, "src/main/resources/mapper").isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        // 兜底：直接信任 user.dir
        return new File(System.getProperty("user.dir"));
    }
}
