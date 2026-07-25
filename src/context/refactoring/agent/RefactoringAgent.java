package context.refactoring.agent;

import com.google.gson.*;
import context.refactoring.CriticalSectionModel;

public class RefactoringAgent {
    private final LLMApiClient apiClient;
    private static final int MAX_ROUNDS = 3;  // 避免无限循环

    public RefactoringAgent(String apiKey) {
        this.apiClient = new LLMApiClient(apiKey);
    }

    public RefactoringCandidate analyze(CriticalSectionModel model) throws Exception {
        String sectionId = model.className + ":" + model.startLine;
        String sectionJson = model.toJsonString();

        String systemPrompt = buildSystemPrompt();

        // 构建初始 messages
        JsonArray messages = new JsonArray();
        messages.add(createMessage("system", systemPrompt));
        messages.add(createMessage("user", "请分析以下临界区：\n" + sectionJson));

        // 工具定义（只定义了 get_write_dependency，可扩展）
        String toolsJson = "[" +
                "  {\"type\":\"function\",\"function\":{" +
                "    \"name\":\"get_write_dependency\"," +
                "    \"description\":\"返回该临界区内写操作之间的数据依赖关系\"," +
                "    \"parameters\":{\"type\":\"object\",\"properties\":{\"section_id\":{\"type\":\"string\"}},\"required\":[\"section_id\"]}" +
                "  }}" +
                "]";

        Gson gson = new Gson();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", "deepseek-chat");
            requestBody.add("messages", messages);
            requestBody.add("tools", gson.fromJson(toolsJson, JsonArray.class));
            requestBody.addProperty("tool_choice", "auto");

            String response = apiClient.sendMessage(gson.toJson(requestBody));
            System.out.println("===== Raw API Response =====");
            System.out.println(response);
            System.out.println("============================");
            JsonObject respJson = gson.fromJson(response, JsonObject.class);

            // 检查是否有错误
            if (respJson.has("error")) {
                System.err.println("API Error: " + respJson);
                return new RefactoringCandidate("not_refactor", "none", "API调用失败", 1);
            }

            JsonObject choice = respJson.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject msg = choice.getAsJsonObject("message");

            // 检查是否有 tool_calls
            if (msg.has("tool_calls")) {
                JsonArray toolCalls = msg.getAsJsonArray("tool_calls");
                // 将 assistant 消息加到 history
                messages.add(msg);

                for (JsonElement tc : toolCalls) {
                    JsonObject toolCall = tc.getAsJsonObject();
                    String toolName = toolCall.get("function").getAsJsonObject().get("name").getAsString();
                    String args = toolCall.get("function").getAsJsonObject().get("arguments").getAsString();

                    // 执行工具
                    ToolExecutor executor = new ToolExecutor(model);
                    String toolResult;
                    if ("get_write_dependency".equals(toolName)) {
                        toolResult = executor.getWriteDependency();
                    } else {
                        toolResult = "{}";
                    }

                    // 添加 tool 消息
                    JsonObject toolMsg = new JsonObject();
                    toolMsg.addProperty("role", "tool");
                    toolMsg.addProperty("tool_call_id", toolCall.get("id").getAsString());
                    toolMsg.addProperty("content", toolResult);
                    messages.add(toolMsg);
                }
                continue; // 继续下一轮
            }

            // 没有 tool_calls，说明输出最终结论
            String content = msg.get("content").getAsString();
            return parseCandidate(content);
        }

        return new RefactoringCandidate("not_refactor", "none", "Agent 超时未确定", 1);
    }
    private String buildSystemPrompt() {
        return "你是一个 Java 并发重构 Agent。\n\n" +
                "## 输入\n" +
                "你会收到一个临界区的结构化 JSON 描述（CriticalSectionModel），包含 className, methodName, syncType, operations, readWritePattern, hasSideEffect, monitorObject 等字段。\n\n" +
                "## 不可重构条件（优先级最高）\n" +
                "如果满足以下任一条件，则必须输出 {\"judgment\":\"not_refactor\",\"pattern\":\"none\",\"reason\":\"...\",\"confidence\":5}：\n" +
                "1. 源码或 operation 中的 methodName 包含 .wait()、.notify() 或 .notifyAll() 调用。\n" +
                "2. 存在锁嵌套（nestedLockInfo 非空）：\n" +
                "   - 如果内外锁对象相同（即 nestedLockInfo 中的锁表达式与当前 monitorObject 相同，如都锁 this 或同一字段），则可继续评估其他模式，但需注意重入和锁顺序。\n" +
                "   - 如果内外锁对象不同（如外层锁 tempCalGMT、内层锁 calendar），则必须判定为不可重构。\n" +
                "3. monitorObject 是方法参数（即不是类字段、也不是 this 或 ClassName.class），因为参数锁对象无法用类级读写锁安全替换。\n" +
                "4. operations 为空列表。\n\n" +
                "## 可重构规则（按优先级从高到低检验）\n\n" +
                "### 模式1: 原子化替换 (atomic_replacement)\n" +
                "条件：(1) 无 IfOp（readWritePattern 不含 'c'）；(2) 无循环（源码不含 for/while）；" +
                "(3) 无数组写（所有 WriteOp.isArrayWrite == false）；" +
                "(4) 无集合方法调用（所有 MethodCallOp.isCollectionMethod == false）；" +
                "(5) 写操作总数 == 1，且该写操作是简单字段写（isFieldWrite==true, isCollectionWrite==false, isArrayWrite==false）；" +
                "(6) hasSideEffect == false。\n\n" +
                "### 模式2: 并发容器替换 (concurrent_container)\n" +
                "条件：(1) 所有 MethodCallOp.isCollectionMethod == true；" +
                "(2) 没有集合字段的直接写操作；" +
                "(3) 没有其他共享字段写操作（WriteOp 的 variable 不属于容器字段的引用）。\n\n" +
                "### 模式3: 锁分解 (lock_splitting)\n" +
                "条件：(1) 无 IfOp（readWritePattern 不含 'c'）；(2) 无循环；(3) 无数组写；" +
                "(4) 至少有2个独立的 WriteOp，且它们的目标变量不同（variable 不同），" +
                "且这些变量之间不存在读写依赖（一个变量的写入依赖于另一个变量的读取）。如果存在依赖，不适合分解。\n" +
                "建议先调用工具 get_write_dependency 确认是否存在依赖。\n\n" +
                "### 模式4: 异步批量更新 (async_batch_update)\n" +
                "条件：(1) 存在 IO 方法调用（MethodCallOp.isIOMethod == true）；" +
                "(2) 写操作数量 ≤3；" +
                "(3) 不存在复杂条件分支（IfOp 数量 ≤1）；" +
                "(4) 写操作不依赖于 IO 操作的结果（由源码判断）。\n\n" +
                "### 模式5: 读写锁替换 (read_write_lock)\n" +
                "条件：不满足以上任一特定模式，但仍然需要同步保护。\n" +
                "子规则：如果 readWritePattern 只包含 'r'（全读），则用读锁；否则用写锁。\n" +
                "注意：如果 monitorObject 是方法参数，则不能使用读写锁替换（已在不可重构条件中处理）。\n\n" +
                "## 输出格式（你必须严格遵守）\n" +
                "你输出的**必须**是一个独立的 JSON 对象，**不能**包含任何额外文字、解释或 Markdown 标记。" +
                "不允许在前面加任何说明前缀（如 \"分析结论如下：\"），也不允许在后面加任何后缀。" +
                "仅输出 JSON 本身。\n\n" +
                "示例：\n" +
                "{\n" +
                "  \"judgment\": \"refactor\",\n" +
                "  \"pattern\": \"read_write_lock\",\n" +
                "  \"reason\": \"简明的中文原因\",\n" +
                "  \"confidence\": 5\n" +
                "}\n\n" +
                "注意：\n" +
                "- 不要使用 ```json 代码块。\n" +
                "- 只输出一个 JSON 对象。\n" +
                "- confidence 必须是 1-5 的整数（不要写成字符串）。\n\n" +
                "如果你需要更多信息（如数据依赖、字段是否 volatile），可以调用工具 get_write_dependency。每轮只调用一个工具。";
    }

    // ============ 私有方法 ============

    /*private String buildSystemPrompt() {
        return "你是一个 Java 并发重构 Agent。\n\n" +
                "## 输入\n" +
                "你会收到一个临界区的结构化 JSON 描述（CriticalSectionModel），包含 className, methodName, syncType, operations, readWritePattern, hasSideEffect 等字段。\n\n" +
                "## 不可重构条件（优先级最高）\n" +
                "如果满足以下任一条件，则必须输出 {\"judgment\":\"not_refactor\",\"pattern\":\"none\",\"reason\":\"...\",\"confidence\":5}：\n" +
                "1. 源码或 operation 中的 methodName 包含 .wait()、.notify() 或 .notifyAll() 调用。\n" +
                "2. nestedLockInfo 非空（存在锁嵌套）。\n" +
                "3. operations 为空列表。\n\n" +
                "## 可重构规则（按优先级从高到低检验）\n\n" +
                "### 模式1: 原子化替换 (atomic_replacement)\n" +
                "条件：(1) 无 IfOp（readWritePattern 不含 'c'）；(2) 无循环（源码不含 for/while）；" +
                "(3) 无数组写（所有 WriteOp.isArrayWrite == false）；" +
                "(4) 无集合方法调用（所有 MethodCallOp.isCollectionMethod == false）；" +
                "(5) 写操作总数 == 1，且该写操作是简单字段写（isFieldWrite==true, isCollectionWrite==false, isArrayWrite==false）；" +
                "(6) hasSideEffect == false。\n\n" +
                "### 模式2: 并发容器替换 (concurrent_container)\n" +
                "条件：(1) 所有 MethodCallOp.isCollectionMethod == true；" +
                "(2) 没有集合字段的直接写操作；" +
                "(3) 没有其他共享字段写操作（WriteOp 的 variable 不属于容器字段的引用）。\n\n" +
                "### 模式3: 锁分解 (lock_splitting)\n" +
                "条件：(1) 无 IfOp（readWritePattern 不含 'c'）；(2) 无循环；(3) 无数组写；" +
                "(4) 至少有2个独立的 WriteOp，且它们的目标变量不同（variable 不同），" +
                "且这些变量之间不存在读写依赖（一个变量的写入依赖于另一个变量的读取）。如果存在依赖，不适合分解。\n" +
                "建议先调用工具 get_write_dependency 确认是否存在依赖。\n\n" +
                "### 模式4: 异步批量更新 (async_batch_update)\n" +
                "条件：(1) 存在 IO 方法调用（MethodCallOp.isIOMethod == true）；" +
                "(2) 写操作数量 ≤3；" +
                "(3) 不存在复杂条件分支（IfOp 数量 ≤1）；" +
                "(4) 写操作不依赖于 IO 操作的结果（由源码判断）。\n\n" +
                "### 模式5: 读写锁替换 (read_write_lock)\n" +
                "条件：不满足以上任一特定模式，但仍然需要同步保护。\n" +
                "子规则：如果 readWritePattern 只包含 'r'（全读），则用读锁；否则用写锁。\n\n" +
                "## 输出格式（你必须严格遵守）\n" +
                "你输出的**必须**是一个独立的 JSON 对象，**不能**包含任何额外文字、解释或 Markdown 标记。" +
                "不允许在前面加任何说明前缀（如 \"分析结论如下：\"），也不允许在后面加任何后缀。" +
                "仅输出 JSON 本身。\n\n" +
                "示例：\n" +
                "{\n" +
                "  \"judgment\": \"refactor\",\n" +
                "  \"pattern\": \"read_write_lock\",\n" +
                "  \"reason\": \"简明的中文原因\",\n" +
                "  \"confidence\": 5\n" +
                "}\n\n" +
                "注意：\n" +
                "- 不要使用 ```json 代码块。\n" +
                "- 只输出一个 JSON 对象。\n" +
                "- confidence 必须是 1-5 的整数（不要写成字符串）。\n\n" +
                "如果你需要更多信息（如数据依赖、字段是否 volatile），可以调用工具 get_write_dependency。每轮只调用一个工具。";
    }*/

    private JsonObject createMessage(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        return msg;
    }
    

    private RefactoringCandidate parseCandidate(String content) {
        // 1. 去除 Markdown 代码块标记（如果存在）
        String cleaned = content.replaceAll("(?s)```(?:json)?\\s*", "").trim();
        
        // 2. 尝试提取最后一个 { ... } 包裹的 JSON 对象
        int lastBraceStart = cleaned.lastIndexOf('{');
        int lastBraceEnd   = cleaned.lastIndexOf('}');
        String jsonStr = null;
        if (lastBraceStart != -1 && lastBraceEnd != -1 && lastBraceEnd > lastBraceStart) {
            jsonStr = cleaned.substring(lastBraceStart, lastBraceEnd + 1).trim();
        } else {
            // 如果没有找到 {}，直接尝试使用清理后的全文
            jsonStr = cleaned;
        }

        Gson gson = new Gson();
        JsonElement element;
        try {
            element = gson.fromJson(jsonStr, JsonElement.class);
        } catch (Exception e) {
            System.err.println("JSON 解析失败，原始内容: " + jsonStr);
            return new RefactoringCandidate("not_refactor", "none", "LLM 返回格式错误: " + jsonStr, 1);
        }

        if (!element.isJsonObject()) {
            System.err.println("LLM 返回非 JSON 对象: " + element.toString());
            return new RefactoringCandidate("not_refactor", "none", "LLM 返回非结构化文本: " + element.toString(), 1);
        }

        JsonObject json = element.getAsJsonObject();

        RefactoringCandidate candidate = new RefactoringCandidate();
        candidate.setJudgment(json.has("judgment") ? json.get("judgment").getAsString() : "unknown");
        candidate.setPattern(json.has("pattern") ? json.get("pattern").getAsString() : "none");
        candidate.setReason(json.has("reason") ? json.get("reason").getAsString() : "无理由");

        // 安全读取 confidence（可能是数字或字符串）
        int confidence = 1;
        if (json.has("confidence")) {
            JsonElement ce = json.get("confidence");
            if (ce.isJsonPrimitive()) {
                if (ce.getAsJsonPrimitive().isNumber()) {
                    confidence = ce.getAsInt();
                } else if (ce.getAsJsonPrimitive().isString()) {
                    try {
                        confidence = Integer.parseInt(ce.getAsString());
                    } catch (NumberFormatException ex) {
                        confidence = 1;
                    }
                }
            }
        }
        candidate.setConfidence(confidence);
        
        if (json.has("implementation_hint")) {
            candidate.setImplementationHint(json.get("implementation_hint").getAsString());
        }
        return candidate;
    }
}