package context.refactoring;

import java.util.ArrayList;
import java.util.List;

public class CriticalSectionModel {
    // 基本元信息
    public String className;
    public String methodName;
    public String syncType;          // "synchronized_method" / "synchronized_block"
    public String monitorObject;
    public int startLine;
    public int endLine;
    public String rawSource;

    // 别名组 ID（由外部填充）
    public int aliasGroupId = -1;

    // 操作序列（核心）
    public List<OpNode> operations = new ArrayList<>();

    // 读写模式字符串（由 generateReadWritePattern 生成）
    public String readWritePattern;

    // 锁嵌套信息
    public List<String> nestedLockInfo = new ArrayList<>();

    // 副作用标记
    public boolean hasSideEffect = false;

    public CriticalSectionModel() {}

    /**
     * 从 operations 生成读写模式字符串，按照论文定义：
     * r - 读操作
     * w - 写操作
     * c - if 条件开始
     * e - if 条件结束
     * (else) 分支标记
     */
    public void generateReadWritePattern() {
        StringBuilder sb = new StringBuilder();
        for (OpNode op : operations) {
            if (op instanceof ReadOp) {
                sb.append("r ");
            } else if (op instanceof WriteOp) {
                sb.append("w ");
            } else if (op instanceof IfOp) {
                IfOp ifOp = (IfOp) op;
                sb.append("c ");
                for (OpNode child : ifOp.thenBranch) {
                    sb.append(opToChar(child)).append(" ");
                }
                if (!ifOp.elseBranch.isEmpty()) {
                    sb.append("(else) ");
                    for (OpNode child : ifOp.elseBranch) {
                        sb.append(opToChar(child)).append(" ");
                    }
                }
                sb.append("e ");
            } else if (op instanceof MethodCallOp) {
                MethodCallOp mco = (MethodCallOp) op;
                sb.append(mco.hasSideEffect ? "w " : "r ");
            } else if (op instanceof SimpleOp) {
                SimpleOp so = (SimpleOp) op;
                sb.append(so.isWrite ? "w " : "r ");
            }
        }
        readWritePattern = sb.toString().trim();
    }

    private char opToChar(OpNode op) {
        if (op instanceof ReadOp) return 'r';
        if (op instanceof WriteOp) return 'w';
        if (op instanceof MethodCallOp) return ((MethodCallOp)op).hasSideEffect ? 'w' : 'r';
        if (op instanceof SimpleOp) return ((SimpleOp)op).isWrite ? 'w' : 'r';
        return 'r'; // 默认读
    }

    // ===================== JSON 序列化 =====================

    /**
     * 将当前模型序列化为 JSON 字符串
     */
    public String toJsonString() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        // 基本信息
        json.append("  \"className\": \"").append(escapeJson(className)).append("\",\n");
        json.append("  \"methodName\": \"").append(escapeJson(methodName)).append("\",\n");
        json.append("  \"syncType\": \"").append(escapeJson(syncType)).append("\",\n");
        json.append("  \"monitorObject\": \"").append(escapeJson(monitorObject)).append("\",\n");
        json.append("  \"startLine\": ").append(startLine).append(",\n");
        json.append("  \"endLine\": ").append(endLine).append(",\n");
        json.append("  \"aliasGroupId\": ").append(aliasGroupId).append(",\n");
        json.append("  \"hasSideEffect\": ").append(hasSideEffect).append(",\n");

        // 原始源码（转义）
        json.append("  \"sourceCode\": \"").append(escapeJson(rawSource)).append("\",\n");

        // 读写模式
        json.append("  \"readWritePattern\": \"").append(escapeJson(readWritePattern)).append("\",\n");

        // 锁嵌套
        json.append("  \"nestedLockInfo\": [\n");
        for (int i = 0; i < nestedLockInfo.size(); i++) {
            json.append("    \"").append(escapeJson(nestedLockInfo.get(i))).append("\"");
            if (i < nestedLockInfo.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");

        // 操作序列
        json.append("  \"operations\": [\n");
        for (int i = 0; i < operations.size(); i++) {
            OpNode op = operations.get(i);
            json.append("    {\n");
            json.append("      \"line\": ").append(op.getLine()).append(",\n");

            if (op instanceof ReadOp) {
                ReadOp ro = (ReadOp) op;
                json.append("      \"type\": \"ReadOp\",\n");
                json.append("      \"variable\": \"").append(escapeJson(ro.variable)).append("\",\n");
                json.append("      \"isFieldRead\": ").append(ro.isFieldRead).append("\n");
            } else if (op instanceof WriteOp) {
                WriteOp wo = (WriteOp) op;
                json.append("      \"type\": \"WriteOp\",\n");
                json.append("      \"variable\": \"").append(escapeJson(wo.variable)).append("\",\n");
                json.append("      \"isFieldWrite\": ").append(wo.isFieldWrite).append(",\n");
                json.append("      \"isCollectionWrite\": ").append(wo.isCollectionWrite).append(",\n");
                json.append("      \"isArrayWrite\": ").append(wo.isArrayWrite).append("\n");
            } else if (op instanceof IfOp) {
                IfOp io = (IfOp) op;
                json.append("      \"type\": \"IfOp\",\n");
                json.append("      \"condition\": \"").append(escapeJson(io.condition)).append("\",\n");
                json.append("      \"thenBranch\": ").append(listOpToJson(io.thenBranch)).append(",\n");
                json.append("      \"elseBranch\": ").append(listOpToJson(io.elseBranch)).append("\n");
            } else if (op instanceof MethodCallOp) {
                MethodCallOp mo = (MethodCallOp) op;
                json.append("      \"type\": \"MethodCallOp\",\n");
                json.append("      \"methodName\": \"").append(escapeJson(mo.methodName)).append("\",\n");
                json.append("      \"receiverType\": \"").append(escapeJson(mo.receiverType)).append("\",\n");
                json.append("      \"hasSideEffect\": ").append(mo.hasSideEffect).append(",\n");
                json.append("      \"isCollectionMethod\": ").append(mo.isCollectionMethod).append(",\n");
                json.append("      \"isIOMethod\": ").append(mo.isIOMethod).append("\n");
            } else if (op instanceof SimpleOp) {
                SimpleOp so = (SimpleOp) op;
                json.append("      \"type\": \"SimpleOp\",\n");
                json.append("      \"expression\": \"").append(escapeJson(so.expression)).append("\",\n");
                json.append("      \"isWrite\": ").append(so.isWrite).append("\n");
            }

            json.append("    }");
            if (i < operations.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");

        json.append("}");
        return json.toString();
    }

    /**
     * 将 List<OpNode> 转成简化的 JSON 数组字符串（用于 if 分支）
     * 为了避免无限嵌套导致 JSON 过大，只输出每个子结点的类型和行号。
     */
    private static String listOpToJson(List<OpNode> nodes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nodes.size(); i++) {
            OpNode op = nodes.get(i);
            sb.append("{");
            sb.append("\"line\":").append(op.getLine()).append(",");
            sb.append("\"type\":\"");
            if (op instanceof ReadOp) sb.append("ReadOp");
            else if (op instanceof WriteOp) sb.append("WriteOp");
            else if (op instanceof IfOp) sb.append("IfOp");
            else if (op instanceof MethodCallOp) sb.append("MethodCallOp");
            else sb.append("SimpleOp");
            sb.append("\"");
            sb.append("}");
            if (i < nodes.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * JSON 字符串转义（处理双引号、反斜杠、控制字符）
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    // 非 printable 字符转义为 Unicode
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}