package context.analysis;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ssa.*;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.classLoader.CallSiteReference;

import java.util.*;

/**
 * 基于 WALA 的负面效应分析器，严格实现论文算法 2.3.2
 * 调用深度限制为 5 层，超限保守返回 true（写操作）
 */
public class NegativeEffectAnalyzer {

    private static final int MAX_DEPTH = 5;
    private final CallGraph callGraph;
    private final PointerAnalysis<InstanceKey> pointerAnalysis;

    public NegativeEffectAnalyzer(CallGraph callGraph, PointerAnalysis<InstanceKey> pointerAnalysis) {
        this.callGraph = callGraph;
        this.pointerAnalysis = pointerAnalysis;
    }

    /**
     * 判断某个 CGNode 的方法体是否有负面效应（写操作）
     */
    public boolean hasSideEffect(CGNode node) {
        return sideEffectAnalysis(node, 0);
    }

    /**
     * 递归分析 IR 指令，判断是否有负面效应
     */
    private boolean sideEffectAnalysis(CGNode node, int depth) {
        if (depth > MAX_DEPTH) {
            return true; // 超限视为写操作
        }
        IR ir = node.getIR();
        if (ir == null) return false;

        for (SSAInstruction instr : ir.getInstructions()) {
            if (instr == null) continue;

            // 1. 写静态字段
            if (instr instanceof SSAPutInstruction) {
                SSAPutInstruction put = (SSAPutInstruction) instr;
                if (put.isStatic()) {
                    return true;
                }
                // 非静态写实例字段
                return true;
            }

            // 2. 写数组元素
            if (instr instanceof SSAArrayStoreInstruction) {
                return true;
            }

            // 3. 方法调用指令：递归分析被调方法
            if (instr instanceof SSAInvokeInstruction) {
                SSAInvokeInstruction invoke = (SSAInvokeInstruction) instr;
                Collection<CGNode> targets = getTargets(node, invoke);
                if (targets.isEmpty()) {
                    // 无法解析调用目标，保守返回 true
                    return true;
                }
                for (CGNode target : targets) {
                    if (sideEffectAnalysis(target, depth + 1)) {
                        return true;
                    }
                }
                // 所有目标都无副作用
                continue;
            }

            // 4. 其他指令不产生负面效应（算术、比较、跳转等）
        }
        return false;
    }

    /**
     * 获取方法调用的所有可能目标 CGNode（多态）
     * 使用 CallGraph.getPossibleTargets
     */
    private Collection<CGNode> getTargets(CGNode caller, SSAInvokeInstruction invoke) {
        // 从 caller 的 IR 中获取调用点
        CallSiteReference site = invoke.getCallSite();
        if (site == null) return Collections.emptyList();

        // 使用 CallGraph 的 getPossibleTargets
        return callGraph.getPossibleTargets(caller, site);
    }

    /**
     * 为外部提供的方法：判断某个方法调用字符串在给定 CGNode 中是否有副作用
     * @param callerNode 调用者节点（当前方法）
     * @param invokedMethodSignature 被调用方法的签名，格式如 "com.example.MyClass.myMethod(Ljava/lang/String;)V"
     * @return true 如果调用有副作用
     */
    public boolean isMethodCallSideEffecting(CGNode callerNode, String invokedMethodSignature) {
        // 通过签名在 CallGraph 中查找对应的 CGNode
        for (CGNode node : callGraph) {
            if (node.getMethod().getSignature().equals(invokedMethodSignature)) {
                return hasSideEffect(node);
            }
        }
        // 未找到对应方法，保守返回 true
        return true;
    }
}