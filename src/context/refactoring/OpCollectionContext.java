package context.refactoring;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;         // 新增导入
import context.analysis.NegativeEffectAnalyzer;

import java.util.ArrayDeque;
import java.util.List;

import org.eclipse.jdt.core.dom.TypeDeclaration;

public class OpCollectionContext {
    public List<OpNode> targetOps;
    public int[] currentDepth;
    public ArrayDeque<String> lockStack;
    public CriticalSectionModel model;
    public NegativeEffectAnalyzer sideEffectAnalyzer;
    public CallGraph callGraph;                       // 新增：CallGraph 对象
    public CGNode callerNode;
    public TypeDeclaration currentType;
    

    public OpCollectionContext(List<OpNode> targetOps, int[] currentDepth,
                               ArrayDeque<String> lockStack, CriticalSectionModel model,
                               NegativeEffectAnalyzer sideEffectAnalyzer,
                               CallGraph callGraph, CGNode callerNode,
                               TypeDeclaration currentType) {  // 新增 callGraph 参数
        this.targetOps = targetOps;
        this.currentDepth = currentDepth;
        this.lockStack = lockStack;
        this.model = model;
        this.sideEffectAnalyzer = sideEffectAnalyzer;
        this.callGraph = callGraph;
        this.callerNode = callerNode;
        this.currentType = currentType;
    }
}