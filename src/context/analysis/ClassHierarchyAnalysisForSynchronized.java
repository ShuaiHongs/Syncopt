package context.analysis;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;

import context.analysis.SynchronizedAnalysis.MonitorInfo;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 桥接 AST 与 WALA，收集 synchronized 行号并调用分析
 */
public class ClassHierarchyAnalysisForSynchronized {

    private SynchronizedAnalysis analysis;
    public int matchedCount = 0;
    private CallGraph callGraph;
    private AnalysisScope analysisScope;
    private IClassHierarchy classHierarchy;  
    public int matchedSyncMethodCount = 0;  // 匹配到的同步方法数
    public int matchedSyncBlockCount  = 0;  // 匹配到的同步块数
    public List<MonitorInfo> allMonitorInfos = new ArrayList<>();
    private PointerAnalysis<InstanceKey> pointerAnalysis;
    

    public ClassHierarchyAnalysisForSynchronized(CallGraph cg, PointerAnalysis<InstanceKey> pointerAnalysis) {
        this.analysis = new SynchronizedAnalysis(cg, pointerAnalysis);
        this.callGraph = cg;
        this.analysisScope = cg.getClassHierarchy().getScope();
        this.classHierarchy = cg.getClassHierarchy(); // 新增
        this.pointerAnalysis = pointerAnalysis;
    }
    
    public PointerAnalysis<InstanceKey> getPointerAnalysis() {
        return pointerAnalysis;
    }
    
    public CallGraph getCallGraph() {
        return callGraph;
    }

    public void printCgStats() {
        int appClassCount = 0;
        for (IClass klass : classHierarchy) {
            if (analysisScope.isApplicationLoader(klass.getClassLoader())) {
                appClassCount++;
            }
        }
        System.out.println("WALA 应用类总数: " + appClassCount);
        System.out.println("CG 节点数: " + callGraph.getNumberOfNodes());
    }
    public boolean isTypeInCHA(String walaTypeName) {
        TypeName tn = TypeName.string2TypeName(walaTypeName);
        TypeReference tr = TypeReference.findOrCreate(ClassLoaderReference.Application, tn);
        IClass klass = classHierarchy.lookupClass(tr);
        return klass != null;
    }

    /**
     * 对指定类的方法进行同步指令对应分析
     * @param typeName WALA 内部类型名，如 "Lcom/example/MyClass"
     * @param md      方法声明
     * @param cu      用于取行号的 CompilationUnit
     */
    /**
     * 对指定类的方法进行同步指令对应分析
     * @param typeName WALA 内部类型名，如 "Lcom/example/MyClass"
     * @param md      方法声明
     * @param cu      用于取行号的 CompilationUnit
     */
    
    public void locateSynchronizedBlocks(String typeName, MethodDeclaration md, CompilationUnit cu) {
        List<Integer> lineNumbers = new ArrayList<>();

        // 1. synchronized 方法本身
        if (Modifier.isSynchronized(md.getModifiers())) {
            int methodLine = cu.getLineNumber(md.getStartPosition());
            lineNumbers.add(methodLine);
        }

        // 2. 方法内部的 synchronized 块
        md.accept(new ASTVisitor() {
            @Override
            public boolean visit(SynchronizedStatement node) {
                int blockLine = cu.getLineNumber(node.getStartPosition());
                lineNumbers.add(blockLine);
                return true;
            }
        });

        // 3. 调用 WALA 分析 —— 新增参数个数传递
        if (!lineNumbers.isEmpty()) {
            // 计算 WALA 期望的参数个数（包含 this 的隐式参数）
            int astParamCount = md.parameters().size();
            boolean isStatic = Modifier.isStatic(md.getModifiers());
            int walaParamCount = astParamCount + (isStatic ? 0 : 1);
            List<SynchronizedAnalysis.MonitorInfo> infos =
                analysis.findMonitors(typeName, md.getName().getIdentifier(), walaParamCount, lineNumbers);
            matchedCount += infos.size();
            allMonitorInfos.addAll(infos);
            
            /*
            System.out.println("  [对应关系] 方法: " + typeName + "." + md.getName().getIdentifier());
            for (MonitorInfo info : infos) {
                String syncType = (info.lockSSAValue == -1) ? "同步方法" : "同步块";
                // 检查WALA行号是否在AST lineNumbers中
                boolean found = lineNumbers.contains(info.lineNumber) ||
                                lineNumbers.contains(info.lineNumber - 1) ||
                                lineNumbers.contains(info.lineNumber + 1);
                System.out.println("    WALA " + syncType + " 行" + info.lineNumber +
                                   " -> AST行号" + (found ? "匹配" : "不匹配"));
            }
            // 输出AST中未匹配的行号
            for (int astLine : lineNumbers) {
                boolean matched = false;
                for (MonitorInfo info : infos) {
                    if (info.lineNumber == astLine || info.lineNumber == astLine-1 || info.lineNumber == astLine+1) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    System.out.println("    [缺失] AST行" + astLine + " 在WALA中未匹配");
                }
            }*/
            //for (SynchronizedAnalysis.MonitorInfo info : infos) {
                //if (info.lockSSAValue == -1) {
                   // matchedSyncMethodCount++;
                //} else {
                   // matchedSyncBlockCount++;
                //}
            //}
            
            
            //Set<Integer> matchedLines = new HashSet<>();
            //for (SynchronizedAnalysis.MonitorInfo info : infos) {
                //matchedLines.add(info.lineNumber);
            //}
            //for (int line : lineNumbers) {
               // if (!matchedLines.contains(line)) {
                 //   System.out.println("[缺失] " + typeName + "." + md.getName().getIdentifier() + " 行 " + line + " 未匹配");
                //}
            //}

            // 如需打印调试信息，取消注释以下代码
            // System.out.println("  方法: " + md.getName().getIdentifier() + 
            //                    " (参数个数: " + walaParamCount + ")");
            // for (SynchronizedAnalysis.MonitorInfo info : infos) {
            //     System.out.println(info);
            // }
        }
        
        
        
        
    }
   
    
}