package context.refactoring.agent;

import context.refactoring.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import context.refactoring.ReadOp;
import context.refactoring.WriteOp;

public class ToolExecutor {
    private final CriticalSectionModel model;

    public ToolExecutor(CriticalSectionModel model) {
        this.model = model;
    }

    /**
     * 分析写操作之间的数据依赖（简化版）
     * 返回 JSON：{"dependencies":["写变量 'x' 依赖于前面的读操作", ...]}
     */
    public String getWriteDependency() {
        List<OpNode> ops = model.operations;
        List<String> readVars = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();

        for (OpNode op : ops) {
            if (op instanceof ReadOp) {
                // 只记录非关键字的、有意义的读操作（可选过滤）
                readVars.add(((ReadOp) op).variable);
            } else if (op instanceof WriteOp) {
                String writeVar = ((WriteOp) op).variable;
                // 如果写操作前出现过同名的读操作，视为数据依赖
                if (readVars.contains(writeVar)) {
                    dependencies.add("写变量 '" + writeVar +  "' 依赖于前面的读操作");
                }
            }
        }
        Gson gson = new Gson();
        JsonObject result = new JsonObject();
        result.add("dependencies", gson.toJsonTree(dependencies));
        return result.toString();
    }

    /**
     * 检查字段是否 volatile（暂未实现完整，返回 false）
     */
    public String checkVolatile(String className, String fieldName) {
        // 实际实现可以从 AST 缓存中查找字段修饰符
        JsonObject result = new JsonObject();
        result.addProperty("isVolatile", false);
        return result.toString();
    }
}