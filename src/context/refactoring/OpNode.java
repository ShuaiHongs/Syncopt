package context.refactoring;
import java.util.Collections;
import java.util.List;
public abstract class OpNode {
    int line;

    public OpNode(int line) {
        this.line = line;
    }

    public int getLine() { return line; }
}

// 读操作
/*class ReadOp extends OpNode {
    String variable;
    boolean isFieldRead;
    public ReadOp(int line, String variable, boolean isFieldRead) {
        super(line);
        this.variable = variable;
        this.isFieldRead = isFieldRead;
    }
}*/

// 写操作
/*class WriteOp extends OpNode {
    String variable;
    boolean isFieldWrite;
    boolean isCollectionWrite;
    boolean isArrayWrite;
    public WriteOp(int line, String variable, boolean isFieldWrite, boolean isCollectionWrite, boolean isArrayWrite) {
        super(line);
        this.variable = variable;
        this.isFieldWrite = isFieldWrite;
        this.isCollectionWrite = isCollectionWrite;
        this.isArrayWrite = isArrayWrite;
    }
}*/

// If 节点（保留嵌套）
class IfOp extends OpNode {
    String condition;
    List<OpNode> thenBranch;
    List<OpNode> elseBranch;
    public IfOp(int line, String condition, List<OpNode> thenBranch, List<OpNode> elseBranch) {
        super(line);
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch == null ? Collections.emptyList() : elseBranch;
    }
}

// 方法调用
class MethodCallOp extends OpNode {
    String methodName;
    String receiverType;           // 通过 WALA 提供
    boolean hasSideEffect;         // 由 WALA 分析
    int callDepth;                 // 递归分析时记录
    boolean isCollectionMethod;
    boolean isIOMethod;
    public MethodCallOp(int line, String methodName, String receiverType) {
        super(line);
        this.methodName = methodName;
        this.receiverType = receiverType;
        this.hasSideEffect = true;   // 保守
        this.callDepth = 0;
        this.isCollectionMethod = false;
        this.isIOMethod = false;
    }
}

// 简单操作（如 i++）
class SimpleOp extends OpNode {
    String expression;
    boolean isWrite;               // 是否是写操作
    public SimpleOp(int line, String expression, boolean isWrite) {
        super(line);
        this.expression = expression;
        this.isWrite = isWrite;
    }
}