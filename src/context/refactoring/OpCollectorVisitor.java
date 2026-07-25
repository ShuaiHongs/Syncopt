package context.refactoring;

import java.util.ArrayList;

import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.core.dom.*;

import com.ibm.wala.ipa.callgraph.CGNode;

import context.analysis.NegativeEffectAnalyzer;

/**
 * 遍历同步体 AST，生成 OpNode 操作序列。
 * 依赖外部传入的 OpCollectionContext（内含 CallGraph、NegativeEffectAnalyzer 等）。
 */
public class OpCollectorVisitor extends ASTVisitor {

    private final OpCollectionContext context;
    private final CompilationUnit cu;

    // 关键字列表（用于跳过 SimpleName），使用 Arrays.asList 以保证 Java 8 兼容
    private static final List<String> KEYWORDS = Arrays.asList(
        "true", "false", "null", "this", "super", "String", "Date",
        "BigDecimal", "Integer", "Long", "Double", "Float", "Boolean",
        "System", "Math", "Logger", "Level", "IOException", "Exception",
        "InterruptedException"
    );

    public OpCollectorVisitor(OpCollectionContext context, CompilationUnit cu) {
        this.context = context;
        this.cu = cu;
    }

    // ===== 赋值 =====
    @Override
    public boolean visit(Assignment node) {
        Expression lhs = node.getLeftHandSide();
        if (isFieldWriteExpression(lhs)) {
            boolean isArray = lhs instanceof ArrayAccess;
            boolean isField = lhs instanceof FieldAccess ||
                              (lhs instanceof SimpleName && context.currentType != null &&
                               findFieldDeclaration(context.currentType, ((SimpleName) lhs).getIdentifier()) != null);
            WriteOp write = new WriteOp(getLine(node), lhs.toString(), isField, false, isArray);
            context.targetOps.add(write);
        }
        return true;
    }

    // ===== 前缀 ++/-- =====
    @Override
    public boolean visit(PrefixExpression node) {
        if (node.getOperator() == PrefixExpression.Operator.INCREMENT
                || node.getOperator() == PrefixExpression.Operator.DECREMENT) {
            Expression operand = node.getOperand();
            if (isFieldWriteExpression(operand)) {
                WriteOp write = new WriteOp(getLine(node), operand.toString(), operand instanceof FieldAccess, false, operand instanceof ArrayAccess);
                context.targetOps.add(write);
            }
        }
        return true;
    }

    @Override
    public boolean visit(PostfixExpression node) {
        if (node.getOperator() == PostfixExpression.Operator.INCREMENT
                || node.getOperator() == PostfixExpression.Operator.DECREMENT) {
            Expression operand = node.getOperand();
            if (isFieldWriteExpression(operand)) {
                WriteOp write = new WriteOp(getLine(node), operand.toString(), operand instanceof FieldAccess, false, operand instanceof ArrayAccess);
                context.targetOps.add(write);
            }
        }
        return true;
    }

    // ===== if 条件判断（递归收集子分支） =====
    @Override
    public boolean visit(IfStatement node) {
        String condition = node.getExpression().toString();
        List<OpNode> thenOps = new ArrayList<>();
        List<OpNode> elseOps = new ArrayList<>();

        collectFromSubtree(node.getThenStatement(), thenOps);
        if (node.getElseStatement() != null) {
            collectFromSubtree(node.getElseStatement(), elseOps);
        }

        IfOp ifOp = new IfOp(getLine(node), condition, thenOps, elseOps);
        context.targetOps.add(ifOp);
        return false; // 子节点已手动处理
    }

    // ===== 方法调用（含深度控制和副作用分析） =====
    @Override
    public boolean visit(MethodInvocation node) {
        String methodName = node.getName().getIdentifier();
        String receiverTypeStr = node.getExpression() != null ? node.getExpression().toString() : "this";
        
        // ===== 判断是否为集合方法（关键修改）=====
        boolean isCollection = false;
        if (node.getExpression() instanceof SimpleName) {
            String receiverName = ((SimpleName) node.getExpression()).getIdentifier();
            if (context.currentType != null) {
                FieldDeclaration field = findFieldDeclaration(context.currentType, receiverName);
                if (field != null) {
                    String fieldType = field.getType().toString();
                    isCollection = isCollectionMethod(methodName) && isContainerType(fieldType);
                }
            }
        } else if (node.getExpression() instanceof FieldAccess) {
            // 处理 this.xxx.method() 的形式
            FieldAccess fa = (FieldAccess) node.getExpression();
            String fieldName = fa.getName().getIdentifier();
            if (context.currentType != null) {
                FieldDeclaration field = findFieldDeclaration(context.currentType, fieldName);
                if (field != null) {
                    String fieldType = field.getType().toString();
                    isCollection = isCollectionMethod(methodName) && isContainerType(fieldType);
                }
            }
        }
        // 注意：如果接收者是对象创建或其他复杂表达式，保持保守，不认为它是集合方法（isCollection 保持 false）

        // ===== IO 方法判断（不变）=====
        boolean isIO = isIOMethod(methodName);

        // ===== 副作用分析（不变）=====
        boolean hasSideEffect;
        if (isCollection || isIO) {
            hasSideEffect = true;
        } else {
            CGNode calleeNode = findCalleeNode(methodName, receiverTypeStr);
            if (calleeNode != null && context.sideEffectAnalyzer != null) {
                hasSideEffect = context.sideEffectAnalyzer.hasSideEffect(calleeNode);
            } else {
                hasSideEffect = true; // 保守默认
            }
        }

        // ===== 构建 MethodCallOp =====
        MethodCallOp mco = new MethodCallOp(getLine(node), node.toString(), receiverTypeStr);
        mco.hasSideEffect = hasSideEffect;
        mco.callDepth = context.currentDepth[0];
        mco.isCollectionMethod = isCollection;
        mco.isIOMethod = isIO;
        context.targetOps.add(mco);

        // 深度控制（不影响 visit 逻辑，只计数）
        if (context.currentDepth[0] < 5) {
            context.currentDepth[0]++;
            context.currentDepth[0]--;
        }
        return true;
    }
    
    private boolean isFieldWriteExpression(Expression expr) {
        if (expr instanceof FieldAccess) {
            return true;
        }
        if (expr instanceof QualifiedName) {
            // 这里保守处理：任何 QualifiedName 都认为是字段（如 Class.field 或 this.field）
            return true;
        }
        if (expr instanceof ArrayAccess) {
            Expression arrayExpr = ((ArrayAccess) expr).getArray();
            // 递归判断数组是否是字段
            return isFieldWriteExpression(arrayExpr);
        }
        if (expr instanceof SimpleName) {
            String name = ((SimpleName) expr).getIdentifier();
            // 检查是否是当前类的字段（通过 findFieldDeclaration 判断）
            return context.currentType != null && findFieldDeclaration(context.currentType, name) != null;
        }
        // 其他情况（如类型字面量、方法调用等保守视为非字段）
        return false;
    }

    // ===== 简单名称（读操作） =====
    @Override
    public boolean visit(SimpleName node) {
        if (isMethodInvocationName(node)) return true;
        if (KEYWORDS.contains(node.getIdentifier())) return true;
        String name = node.getIdentifier();
        boolean isField = isFieldAccessReference(node);
        ReadOp read = new ReadOp(getLine(node), name, isField);
        context.targetOps.add(read);
        return true;
    }

    // ===== 锁嵌套检测 =====
    @Override
    public boolean visit(SynchronizedStatement node) {
        String currentLock = node.getExpression().toString();
        if (!context.lockStack.isEmpty()) {
            String outerLock = context.lockStack.peek();
            context.model.nestedLockInfo.add("锁嵌套: 外层=" + outerLock + " 内层=" + currentLock);
        }
        context.lockStack.push(currentLock);
        return true;
    }

    @Override
    public void endVisit(SynchronizedStatement node) {
        context.lockStack.pop();
    }

    // ==============================================================
    // 工具方法
    // ==============================================================

    private int getLine(ASTNode node) {
        return cu.getLineNumber(node.getStartPosition());
    }

    private boolean isCollectionMethod(String name) {
        return name.equals("get") || name.equals("put") || name.equals("add")
                || name.equals("remove") || name.equals("contains")
                || name.equals("containsKey") || name.equals("size")
                || name.equals("clear") || name.equals("push") || name.equals("pop")
                || name.equals("offer") || name.equals("poll");
    }

    private boolean isIOMethod(String name) {
        return name.startsWith("print") || name.startsWith("log")
                || name.startsWith("write") || name.startsWith("flush")
                || name.startsWith("send");
    }

    private boolean isMethodInvocationName(SimpleName node) {
        ASTNode parent = node.getParent();
        if (parent instanceof MethodInvocation) {
            return ((MethodInvocation) parent).getName() == node;
        }
        return false;
    }
    
    private FieldDeclaration findFieldDeclaration(TypeDeclaration td, String name) {
        for (FieldDeclaration fd : td.getFields()) {
            for (Object obj : fd.fragments()) {
                if (obj instanceof VariableDeclarationFragment) {
                    VariableDeclarationFragment frag = (VariableDeclarationFragment) obj;
                    if (frag.getName().getIdentifier().equals(name)) {
                        return fd;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 判断类型名是否属于容器类型（集合、映射、队列等）。
     */
    private boolean isContainerType(String typeName) {
        String upper = typeName.toUpperCase();
        return upper.contains("MAP") || upper.contains("LIST") 
            || upper.contains("SET") || upper.contains("QUEUE")
            || upper.contains("COLLECTION") || upper.contains("VECTOR")
            || upper.contains("HASHTABLE") || upper.contains("STACK")
            || upper.contains("ARRAYLIST") || upper.contains("HASHMAP")
            || upper.contains("CONCURRENTHASHMAP") || upper.contains("COPYONWRITE")
            || upper.contains("CONCURRENTLINKED");
    }

    private boolean isFieldAccessReference(SimpleName node) {
        ASTNode parent = node.getParent();
        if (parent instanceof FieldAccess) {
            return ((FieldAccess) parent).getName() == node;
        }
        if (parent instanceof QualifiedName) {
            return ((QualifiedName) parent).getName() == node;
        }
        return false;
    }

    /**
     * 从 CallGraph 中根据方法名和接收者类型查找可能的 CGNode。
     * 注意：此处通过 context.callGraph 遍历（CGNode 没有 getCallGraph 方法）。
     */
    private CGNode findCalleeNode(String methodName, String receiverType) {
        if (context.callGraph == null) return null;
        for (CGNode node : context.callGraph) {
            if (node.getMethod().getName().toString().equals(methodName)) {
                String declaringClass = node.getMethod().getDeclaringClass().getName().toString();
                if (receiverType.equals("this") || declaringClass.endsWith(receiverType)) {
                    return node;
                }
            }
        }
        return null;
    }

    /**
     * 递归收集子 AST 节点的操作，用于 if 分支。
     */
    private void collectFromSubtree(ASTNode node, List<OpNode> targetSubList) {
        OpCollectionContext subContext = new OpCollectionContext(
            targetSubList,
            context.currentDepth,
            context.lockStack,
            context.model,
            context.sideEffectAnalyzer,
            context.callGraph,
            context.callerNode,
            context.currentType
        );
        OpCollectorVisitor subVisitor = new OpCollectorVisitor(subContext, cu);
        node.accept(subVisitor);
    }
}