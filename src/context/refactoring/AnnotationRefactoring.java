package context.refactoring;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import context.analysis.LockAliasAnalyzer;
import context.analysis.SynchronizedAnalysis.MonitorInfo;
import context.refactoring.agent.RefactoringAgent;
import context.refactoring.agent.RefactoringCandidate;
import context.refactoring.pattern.AtomicReplacementRefactoring;
import context.refactoring.pattern.ConcurrentContainerRefactoring;
import context.refactoring.pattern.LockSplittingRefactoring;
import context.refactoring.pattern.NoRefactoring;
import context.refactoring.pattern.ReadWriteLockRefactoring;
import context.refactoring.pattern.RefactoringPattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import org.eclipse.jface.text.Document;

import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextFileChange;

import org.eclipse.text.edits.TextEdit;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.cha.ClassHierarchyException;

import context.analysis.ClassHierarchyAnalysisForSynchronized;
import context.analysis.NegativeEffectAnalyzer;
import context.analysis.MakeCallGraph;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;  

//行号匹配在ClassHierarchyAnalysisForSynchronized.locateSynchronizedBlocks
public class AnnotationRefactoring extends Refactoring {

	private List<RefactoringCandidate> agentCandidates = new ArrayList<>();
    List<Change> fChangeManager = new ArrayList<Change>();
    List<IJavaElement> compilationUnits = new ArrayList<IJavaElement>();

    File file;
    IPath filepath;

    // 新增：WALA 同步分析器
    private ClassHierarchyAnalysisForSynchronized syncAnalyzer;
    
	private Map<String, RefactoringPattern> patternMap = new HashMap<>();

    public AnnotationRefactoring(IJavaElement element) throws IOException {
        filepath = element.getJavaProject().getProject().getLocation();
        file = new File(filepath.toString() + "\\ContextExtraction");

        if (!file.exists()) {
            file.mkdir();
        }

        findAllCompilationUnits(element.getJavaProject());

        // ====== 初始化 WALA 分析器 ======
        try {
        	IPath classPath = filepath.append("bin");
        	System.out.println(classPath);
        	MakeCallGraph mcg = new MakeCallGraph(classPath);
            syncAnalyzer = new ClassHierarchyAnalysisForSynchronized(mcg.cg, mcg.pointerAnalysis);
        } catch (ClassHierarchyException | CallGraphBuilderCancelException | IllegalArgumentException e) {
            e.printStackTrace();
            syncAnalyzer = null; // 初始化失败则跳过 WALA 分析
        }
        initPatternMap();
    }
    
    private void initPatternMap() {
        patternMap.put("read_write_lock", new ReadWriteLockRefactoring());
        patternMap.put("concurrent_container", new ConcurrentContainerRefactoring());
        patternMap.put("not_refactor", new NoRefactoring());
        patternMap.put("lock_splitting", new LockSplittingRefactoring());
        patternMap.put("atomic_replacement", new AtomicReplacementRefactoring());
        patternMap.put("async_batch_update", new NoRefactoring());
    }
   
    @Override
    public RefactoringStatus checkFinalConditions(IProgressMonitor pm)
            throws CoreException, OperationCanceledException {
        try {
            collectChanges();
        } catch (JavaModelException | IOException e) {
            e.printStackTrace();
        }
        return RefactoringStatus.createInfoStatus("Context has been extracted??");
    }

    @Override
    public RefactoringStatus checkInitialConditions(IProgressMonitor pm)
            throws CoreException, OperationCanceledException {
        return RefactoringStatus.createInfoStatus("Initial Condition is OK!");
    }

    @Override
    public Change createChange(IProgressMonitor monitor)
            throws CoreException, OperationCanceledException {
        Change[] changes = new Change[fChangeManager.size()];
        System.arraycopy(fChangeManager.toArray(), 0, changes, 0, fChangeManager.size());
        CompositeChange change = new CompositeChange("Context Extraction", changes);
        return change;
    }

    @Override
    public String getName() {
        return "refactoring synchronized";
    }


    private static class CriticalSectionInfo {
        String className;
        String methodName;
        String syncType;       // synchronized_method / synchronized_block
        String monitorObject;  // this / ClassName.class / lock expression
        int startLine;
        int endLine;

        List<String> readSet = new ArrayList<String>();
        List<String> writeSet = new ArrayList<String>();
        List<String> ifConditions = new ArrayList<String>();
        List<String> collectionAccesses = new ArrayList<String>();
        List<String> methodCalls = new ArrayList<String>();
        List<String> simpleOperations = new ArrayList<String>();
        List<String> semanticHints = new ArrayList<String>();

    }

    @SuppressWarnings("deprecation")
    private void collectChanges() throws JavaModelException, IOException {
        int[] astSyncMethodCount = {0};
        int[] astSyncBlockCount = {0};

        // 重置 WALA 计数器
        if (syncAnalyzer != null) {
            syncAnalyzer.matchedCount = 0;
        }

        // 存放所有同步点的特征模型
        List<CriticalSectionModel> allModels = new ArrayList<>();

        // 创建 NegativeEffectAnalyzer（如果可用）
        final NegativeEffectAnalyzer nea = (syncAnalyzer != null)
        	    ? new NegativeEffectAnalyzer(syncAnalyzer.getCallGraph(), syncAnalyzer.getPointerAnalysis())
        	    : null;

        for (IJavaElement element : compilationUnits) {
            ICompilationUnit cu = (ICompilationUnit) element;
            String source = cu.getSource();
            Document document = new Document(source);

            ASTParser parser = ASTParser.newParser(AST.JLS8);
            parser.setSource(cu);
            CompilationUnit astRoot = (CompilationUnit) parser.createAST(null);
            astRoot.recordModifications();

            // 获取包名
            String packageName = "";
            if (astRoot.getPackage() != null) {
                packageName = astRoot.getPackage().getName().getFullyQualifiedName();
            }

            List<TypeDeclaration> types = new ArrayList<>();
            getClasses(astRoot, types);

            for (TypeDeclaration td : types) {
                String className = td.getName().getIdentifier();
                String walaTypeName = "L" + packageName.replace('.', '/') + "/" + className;

                List<MethodDeclaration> methods = new ArrayList<>();
                getMethods(td, methods);

                for (MethodDeclaration md : methods) {
                    // ====== 原有 AST 同步统计 ======
                    if (Modifier.isSynchronized(md.getModifiers())) {
                        astSyncMethodCount[0]++;
                    }
                    md.accept(new ASTVisitor() {
                        @Override
                        public boolean visit(SynchronizedStatement node) {
                            astSyncBlockCount[0]++;
                            return true;
                        }
                    });

                    // 计算 WALA 参数个数
                    int astParamCount = md.parameters().size();
                    boolean isStatic = Modifier.isStatic(md.getModifiers());
                    int walaParamCount = astParamCount + (isStatic ? 0 : 1);

                    // 查找当前方法对应的 CGNode
                    CGNode callerNode = findCGNode(walaTypeName, md.getName().getIdentifier(), walaParamCount);

                    // ====== 同步方法模型 ======
                    if (Modifier.isSynchronized(md.getModifiers())) {
                        String monitorObject = isStatic ? className + ".class" : "this";
                        CriticalSectionModel model = buildModelFromSynchronizedBody(
                            td, md, astRoot, "synchronized_method", monitorObject,
                            md.getBody(), nea, syncAnalyzer != null ? syncAnalyzer.getCallGraph() : null, callerNode);
                        allModels.add(model);
                    }

                    // ====== 同步块模型 ======
                    md.accept(new ASTVisitor() {
                        @Override
                        public boolean visit(SynchronizedStatement node) {
                            CriticalSectionModel model = buildModelFromSynchronizedBody(
                                td, md, astRoot, "synchronized_block", node.getExpression().toString(),
                                node.getBody(), nea, syncAnalyzer != null ? syncAnalyzer.getCallGraph() : null, callerNode);
                            allModels.add(model);
                            return true;
                        }
                    });

                    // ====== WALA 桥接（保留原有行号匹配） ======
                    if (syncAnalyzer != null) {
                        syncAnalyzer.locateSynchronizedBlocks(walaTypeName, md, astRoot);
                    }

                    // 原有的 getSynchronized 输出已由模型取代，可注释或删除
                    // getSynchronized(td, md, astRoot);
                }
            }

            //TextEdit edits = astRoot.rewrite(document, cu.getJavaProject().getOptions(true));
            //TextFileChange change = new TextFileChange("", (IFile) cu.getResource());
            //change.setEdit(edits);
            //fChangeManager.add(change);
        }

        // ====== 别名分组分析（原有逻辑，不变） ======
        if (syncAnalyzer != null && !syncAnalyzer.allMonitorInfos.isEmpty()) {
            LockAliasAnalyzer aliasAnalyzer = new LockAliasAnalyzer(syncAnalyzer.getPointerAnalysis());

            List<List<MonitorInfo>> groups = new ArrayList<>();
            for (MonitorInfo mi : syncAnalyzer.allMonitorInfos) {
                boolean added = false;
                for (List<MonitorInfo> group : groups) {
                    if (aliasAnalyzer.mayAlias(mi, group.get(0))) {
                        group.add(mi);
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    List<MonitorInfo> newGroup = new ArrayList<>();
                    newGroup.add(mi);
                    groups.add(newGroup);
                }
            }

            // 输出别名分组信息
            System.out.println("\n========== 锁对象别名分析结果 ==========");
            System.out.println("共有 " + groups.size() + " 组不同的锁对象：");
            for (int i = 0; i < groups.size(); i++) {
                List<MonitorInfo> group = groups.get(i);
                System.out.println("--- 锁组 " + (i+1) + " (包含 " + group.size() + " 个同步点) ---");
                for (MonitorInfo mi : group) {
                    String type = (mi.lockSSAValue == -1 ? "同步方法" : "同步块");
                    String cls = mi.cgNode.getMethod().getDeclaringClass().getName().toString();
                    String method = mi.cgNode.getMethod().getName().toString();
                    String lockDesc = aliasAnalyzer.describeLock(mi.cgNode, mi.lockSSAValue);
                    System.out.println("  行" + mi.lineNumber + " [" + type + "] 类:" + cls + " 方法:" + method + " 锁:" + lockDesc);
                }
            }
            System.out.println("=======================================\n");

            // 将别名组 ID 填充到每个 CriticalSectionModel
            for (int gid = 0; gid < groups.size(); gid++) {
                for (MonitorInfo mi : groups.get(gid)) {
                    for (CriticalSectionModel cm : allModels) {
                        // 通过行号匹配（±1 容差）
                        if (cm.startLine == mi.lineNumber || cm.startLine == mi.lineNumber - 1
                                || cm.startLine == mi.lineNumber + 1) {
                            cm.aliasGroupId = gid;
                            break; // 避免多次设置
                        }
                    }
                }
            }
        }

        // ====== 输出特征明细 ======
        
        StringBuilder jsonArrayBuilder = new StringBuilder();
        jsonArrayBuilder.append("[\n");
        for (int i = 0; i < allModels.size(); i++) {
            jsonArrayBuilder.append(allModels.get(i).toJsonString());
            if (i < allModels.size() - 1) {
                jsonArrayBuilder.append(",\n");
            } else {
                jsonArrayBuilder.append("\n");
            }
        }
        jsonArrayBuilder.append("]");
        //System.out.println(jsonArrayBuilder.toString());
        
        //LLM agent调用
        /*String apiKey = "sk-60ad932d53d44e0c916eb17f2bde07bf";  // 建议从配置文件读取
        RefactoringAgent agent = new RefactoringAgent(apiKey);
        File resultFile = new File(file, "analysis_result.txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(resultFile, true))) {
        	System.out.println("ContextExtraction 目录路径: " + file.getAbsolutePath());
        	System.out.println("目标文件路径: " + new File(file, "analysis_result.txt").getAbsolutePath());
            // 如果文件是新创建的，可以写入一个表头（可选）
            if (!resultFile.exists() || resultFile.length() == 0) {
                writer.write("重构类型<类名.方法名:行号,同步类型,锁对象,置信度,原因>");
                writer.newLine();
            }

            System.out.println("\n===== Agent 分析结果（逐个写入文件）=====");
            for (int i = 0; i < allModels.size(); i++) {
                CriticalSectionModel model = allModels.get(i);
                RefactoringCandidate candidate;
                try {
                    candidate = agent.analyze(model);
                } catch (Exception e) {
                    System.err.println("分析出错: " + e.getMessage());
                    // 出错时生成一个默认候选，避免跳过写入
                    candidate = new RefactoringCandidate("not_refactor", "none", "分析异常: " + e.getMessage(), 1);
                }

                // 构建一行输出字符串
                String patternName;
                if ("not_refactor".equals(candidate.getJudgment())) {
                    patternName = "不重构";
                } else {
                    switch (candidate.getPattern()) {
                        case "atomic_replacement":      patternName = "原子化替换"; break;
                        case "concurrent_container":    patternName = "并发容器替换"; break;
                        case "lock_splitting":          patternName = "锁分解"; break;
                        case "async_batch_update":      patternName = "异步批量更新"; break;
                        case "read_write_lock":         patternName = "读写锁替换"; break;
                        default:                        patternName = "未知"; break;
                    }
                }

                String syncTypeDisplay = "synchronized_method".equals(model.syncType) ? "同步方法" : "同步块";
                String lockDisplay = "synchronized_block".equals(model.syncType) ? model.monitorObject : "";

                String line = String.format("%s<%s.%s:%d,%s,%s,%d,%s>%n",
                        patternName,
                        model.className,
                        model.methodName,
                        model.startLine,
                        syncTypeDisplay,
                        lockDisplay,
                        candidate.getConfidence(),
                        candidate.getReason());

                // 写入文件
                writer.write(line);
                writer.flush();  // 立即写入，确保不丢失

                // 控制台输出
                System.out.println("[" + model.className + "." + model.methodName + " 行" + model.startLine + "-" + model.endLine + "]");
                System.out.println("  模式: " + candidate.getPattern());
                System.out.println("  判断: " + candidate.getJudgment());
                System.out.println("  原因: " + candidate.getReason());
                System.out.println("  置信度: " + candidate.getConfidence());
            }
            System.out.println("分析结果已逐个写入文件: " + resultFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("写入文件失败: " + e.getMessage());
            e.printStackTrace();
        }
        */
        //读取分析结果
     // ====== 读取 analysis_result.txt，构建重构决策映射 ======
        Map<String, String> refactorDecisions = new HashMap<>(); // key: "类名.方法名:行号", value: 内部模式名
        File resultFile1 = new File(file, "analysis_result.txt");
        System.out.println("读取分析结果文件: " + resultFile1.getAbsolutePath());
        if (resultFile1.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(resultFile1))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("重构类型<")) continue; // 跳过空行和表头

                    int bracketStart = line.indexOf('<');
                    int bracketEnd = line.lastIndexOf('>');
                    if (bracketStart == -1 || bracketEnd == -1) continue;

                    String patternName = line.substring(0, bracketStart).trim(); // 如 "原子化替换"
                    String content = line.substring(bracketStart + 1, bracketEnd);
                    String[] parts = content.split(",");
                    if (parts.length < 2) continue;

                    String classMethodLine = parts[0].trim(); // "类名.方法名:行号"
                    String internalPattern = mapPatternToInternal(patternName);
                    refactorDecisions.put(classMethodLine, internalPattern);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        Map<String, CriticalSectionModel> modelMap = new HashMap<>();
        for (CriticalSectionModel m : allModels) {
            String baseKey = m.className + "." + m.methodName + ":" + m.startLine;
            modelMap.put(baseKey, m);
            modelMap.put(m.className + "." + m.methodName + ":" + (m.startLine - 1), m);
            modelMap.put(m.className + "." + m.methodName + ":" + (m.startLine + 1), m);
        }
        
        for (IJavaElement element : compilationUnits) {
        	 ICompilationUnit cu = (ICompilationUnit) element;
        	    String source = cu.getSource();
        	    Document document = new Document(source);

        	    ASTParser parser = ASTParser.newParser(AST.JLS8);
        	    parser.setSource(cu);
        	    CompilationUnit astRoot = (CompilationUnit) parser.createAST(null);
        	    astRoot.recordModifications();  // 开启修改记录

        	    // 收集所有顶层类
        	    List<TypeDeclaration> types = new ArrayList<>();
        	    astRoot.accept(new ASTVisitor() {
        	        @Override
        	        public boolean visit(TypeDeclaration node) {
        	            types.add(node);
        	            return true;
        	        }
        	    });
        	  
        	    for (TypeDeclaration td : types) {
        	        String className = td.getName().getIdentifier();

        	        for (MethodDeclaration md : td.getMethods()) {
        	            String methodKeyPrefix = className + "." + md.getName().getIdentifier() + ":";

        	            // 1. 同步方法：使用 methodLine 避免命名冲突
        	            if (Modifier.isSynchronized(md.getModifiers())) {
        	                int methodLine = astRoot.getLineNumber(md.getStartPosition());
        	                String key = methodKeyPrefix + methodLine;
        	                String pattern = refactorDecisions.get(key);
        	                if (pattern != null && !"not_refactor".equals(pattern)) {
        	                    // 调用新签名的 applyMethodRefactoring，传入行号和 modelMap
        	                    applyMethodRefactoring(td, md, pattern, astRoot, methodLine, modelMap);
        	                }
        	            } else {
        	                // 2. 非同步方法：遍历其内部的同步块，使用 blockLine
        	                md.accept(new ASTVisitor() {
        	                    @Override
        	                    public boolean visit(SynchronizedStatement node) {
        	                        int blockLine = astRoot.getLineNumber(node.getStartPosition());
        	                        String key = methodKeyPrefix + blockLine;
        	                        String pattern = refactorDecisions.get(key);
        	                        if (pattern != null && !"not_refactor".equals(pattern)) {
        	                            applyBlockRefactoring(td, md, node, pattern, astRoot, blockLine, modelMap);
        	                        }
        	                        return true;
        	                    }
        	                });
        	            }
        	        }
        	    }

        	    // 生成 TextEdit 并添加到 fChangeManager
        	    TextEdit edits = astRoot.rewrite(document, cu.getJavaProject().getOptions(true));
        	    if (edits != null && edits.getChildrenSize() > 0) {
        	        TextFileChange change = new TextFileChange("", (IFile) cu.getResource());
        	        change.setEdit(edits);
        	        fChangeManager.add(change);
        	    }
        	
        	
        }
            
            
        
        
        //System.out.println("\n===== Agent 分析结果 =====");
        //for (CriticalSectionModel model : allModels) {
            //try {
                //RefactoringCandidate candidate = agent.analyze(model);
                //System.out.println("[" + model.className + "." + model.methodName + " 行" + model.startLine + "-" + model.endLine + "]");
                //System.out.println("  模式: " + candidate.getPattern());
                //System.out.println("  判断: " + candidate.getJudgment());
                //System.out.println("  原因: " + candidate.getReason());
                //System.out.println("  置信度: " + candidate.getConfidence());
            //} catch (Exception e) {
                //System.err.println("分析出错: " + e.getMessage());
            //}
        //}
        System.out.println("分析结束");
        //writeAnalysisResultToFile(allModels);
        /*System.out.println("\n========== 同步点特征明细 ==========");
        for (CriticalSectionModel cm : allModels) {
            System.out.println("--- " + cm.syncType + " : " + cm.className + "." + cm.methodName
                    + " 行 " + cm.startLine + "-" + cm.endLine + " ---");
            System.out.println("监视器对象: " + cm.monitorObject);
            
            if (cm.operations.isEmpty()) {
                // 无 WALA 分析的模式：只输出原始代码
                System.out.println("（无 WALA 分析，仅保留原始代码）");
                System.out.println("原始代码:\n" + cm.rawSource);
                System.out.println();
                continue;
            }
            
            // 有 WALA 分析的模式：输出完整特征
            System.out.println("别名组 ID: " + cm.aliasGroupId);
            System.out.println("原始代码:\n" + cm.rawSource);
            System.out.println("锁嵌套: " + (cm.nestedLockInfo.isEmpty() ? "无" : cm.nestedLockInfo));
            System.out.println("操作序列:");
            // ... 原有操作序列输出代码 ...
            System.out.println("操作序列:");
            if (cm.operations.isEmpty()) {
                System.out.println("  (空)");
            } else {
                for (OpNode op : cm.operations) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("  [行").append(op.getLine()).append("] ");
                    if (op instanceof ReadOp) {
                        ReadOp ro = (ReadOp) op;
                        sb.append("ReadOp: variable=").append(ro.variable)
                          .append(", isFieldRead=").append(ro.isFieldRead);
                    } else if (op instanceof WriteOp) {
                        WriteOp wo = (WriteOp) op;
                        sb.append("WriteOp: variable=").append(wo.variable)
                          .append(", isFieldWrite=").append(wo.isFieldWrite)
                          .append(", isCollectionWrite=").append(wo.isCollectionWrite)
                          .append(", isArrayWrite=").append(wo.isArrayWrite);
                    } else if (op instanceof IfOp) {
                        IfOp io = (IfOp) op;
                        sb.append("IfOp: condition=\"").append(io.condition)
                          .append("\", thenCount=").append(io.thenBranch.size())
                          .append(", elseCount=").append(io.elseBranch.size());
                    } else if (op instanceof MethodCallOp) {
                        MethodCallOp mo = (MethodCallOp) op;
                        sb.append("MethodCallOp: method=").append(mo.methodName)
                          .append(", receiver=").append(mo.receiverType)
                          .append(", hasSideEffect=").append(mo.hasSideEffect)
                          .append(", isCollection=").append(mo.isCollectionMethod)
                          .append(", isIO=").append(mo.isIOMethod);
                    } else if (op instanceof SimpleOp) {
                        SimpleOp so = (SimpleOp) op;
                        sb.append("SimpleOp: expression=").append(so.expression)
                          .append(", isWrite=").append(so.isWrite);
                    }
                    System.out.println(sb.toString());
                }
            }
            System.out.println("读写模式序列: " + cm.readWritePattern);
            System.out.println("是否有副作用: " + cm.hasSideEffect);
            System.out.println();
        }
        System.out.println("========================================");*/

        // ====== 输出最终统计（保留） ======
        System.out.println("==================================================");
        System.out.println("=== 同步点统计汇总 ===");
        System.out.println("AST 全源码同步方法数: " + astSyncMethodCount[0]);
        System.out.println("AST 全源码同步块数:  " + astSyncBlockCount[0]);
        System.out.println("AST 总数:            " + (astSyncMethodCount[0] + astSyncBlockCount[0]));
        if (syncAnalyzer != null) {
            System.out.println("WALA 可达同步点总数:  " + syncAnalyzer.matchedCount);
            System.out.println("(WALA 仅统计从 main 出发可达的方法内的同步点)");
        }
        System.out.println("==================================================");
    }
    
   /* private void writeAnalysisResultToFile(List<CriticalSectionModel> allModels) throws IOException {
        if (allModels.size() != agentCandidates.size()) {
            System.err.println("模型数量与候选结果数量不一致，跳过文件写入");
            return;
        }

        File resultFile = new File(file, "spejbb_analysis_result.txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(resultFile))) {
            for (int i = 0; i < allModels.size(); i++) {
                CriticalSectionModel model = allModels.get(i);
                RefactoringCandidate candidate = agentCandidates.get(i);

                // 确定重构类型名称
                String patternName;
                if ("not_refactor".equals(candidate.getJudgment())) {
                    patternName = "不重构";
                } else {
                    // 将 pattern 转换为中文名称
                    switch (candidate.getPattern()) {
                        case "atomic_replacement":      patternName = "原子化替换"; break;
                        case "concurrent_container":    patternName = "并发容器替换"; break;
                        case "lock_splitting":          patternName = "锁分解"; break;
                        case "async_batch_update":      patternName = "异步批量更新"; break;
                        case "read_write_lock":         patternName = "读写锁替换"; break;
                        default:                        patternName = "未知"; break;
                    }
                }

                // 确认同步类型显示
                String syncTypeDisplay = "synchronized_method".equals(model.syncType) ? "同步方法" : "同步块";
                String lockDisplay = "synchronized_block".equals(model.syncType) ? model.monitorObject : "";

                // 构建输出行：重构类型<类名.方法名:行号,同步类型,锁对象,原因>
                // 格式自由，可按需调整
                String line = String.format("%s<%s.%s:%d,%s,%s,%s>%n",
                        patternName,
                        model.className,
                        model.methodName,
                        model.startLine,            // 使用开始行号
                        syncTypeDisplay,
                        lockDisplay,
                        candidate.getReason()
                );
                writer.write(line);
            }
        }
        System.out.println("分析结果已写入文件: " + resultFile.getAbsolutePath());
    }
    */

 
    
    
    private String mapPatternToInternal(String chinesePattern) {
        switch (chinesePattern) {
            case "原子化替换":   return "atomic_replacement";
            case "并发容器替换": return "concurrent_container";
            case "锁分解":       return "lock_splitting";
            case "异步批量更新": return "async_batch_update";
            case "读写锁替换":   return "read_write_lock";
            case "不重构":       return "not_refactor";
            default:             return "unknow";
        }
    }
    private CGNode findCGNode(String typeName, String methodName, int paramCount) {
        if (syncAnalyzer == null) return null;
        CallGraph cg = syncAnalyzer.getCallGraph();
        if (cg == null) return null;
        for (CGNode node : cg) {
            if (node.getMethod().getDeclaringClass().getName().toString().equals(typeName)
                    && node.getMethod().getName().toString().equals(methodName)
                    && node.getMethod().getNumberOfParameters() == paramCount) {
                return node;
            }
        }
        return null;
    }
    
    private CriticalSectionModel buildModelFromSynchronizedBody(
            TypeDeclaration td, MethodDeclaration md,
            CompilationUnit cu, String syncType, String monitorObject,
            ASTNode body, NegativeEffectAnalyzer sideEffectAnalyzer,
            CallGraph callGraph, CGNode callerNode) {

        CriticalSectionModel model = new CriticalSectionModel();
        model.className = td.getName().getIdentifier();
        model.methodName = md.getName().getIdentifier();
        model.syncType = syncType;
        model.monitorObject = monitorObject;

        // 行号：使用传入的 body 起始/结束位置
        //model.startLine = cu.getLineNumber(body.getStartPosition());
        if ("synchronized_method".equals(syncType)) {
            // 与方法声明起始行对齐
            model.startLine = cu.getLineNumber(md.getStartPosition());
        } else if ("synchronized_block".equals(syncType)) {
            // 与 synchronized 语句起始行对齐
            ASTNode parent = body.getParent();
            if (parent instanceof SynchronizedStatement) {
                model.startLine = cu.getLineNumber(parent.getStartPosition());
            } else {
                model.startLine = cu.getLineNumber(body.getStartPosition());
            }
        }
        model.endLine = cu.getLineNumber(body.getStartPosition() + body.getLength());

        // ===== 修正原始源码（rawSource）=====
        if ("synchronized_method".equals(syncType)) {
            // 同步方法：记录整个方法声明（包括 synchronized 修饰符、返回类型、参数、方法体）
            model.rawSource = md.toString();
        } else if ("synchronized_block".equals(syncType)) {
            // 同步块：body 的父节点是 SynchronizedStatement，输出整个 synchronized(...) { ... }
            ASTNode parent = body.getParent();
            if (parent instanceof SynchronizedStatement) {
                model.rawSource = parent.toString();
            } else {
                // 意外情况时，至少输出 body
                model.rawSource = body.toString();
            }
        } else {
            // 其他情况（理论上不应到达）
            model.rawSource = body.toString();
        }

        // ===== 操作序列收集（原有逻辑不变）=====
        int[] depth = {0};
        java.util.ArrayDeque<String> lockStack = new java.util.ArrayDeque<>();
        OpCollectionContext context = new OpCollectionContext(
            model.operations, depth, lockStack, model,
            sideEffectAnalyzer, callGraph, callerNode,td);
        OpCollectorVisitor visitor = new OpCollectorVisitor(context, cu);
        body.accept(visitor);

        // 生成读写模式字符串
        model.generateReadWritePattern();

        // ===== 副作用检测（保留）=====
        model.hasSideEffect = false;  // 默认为 false
        for (OpNode op : model.operations) {
            if (op instanceof WriteOp) {
                model.hasSideEffect = true;
                break;
            }
            if (op instanceof MethodCallOp && ((MethodCallOp)op).hasSideEffect) {
                model.hasSideEffect = true;
                break;
            }
        }

        return model;
    }
    

    // ====================== 原有辅助方法 ======================

    private void getClasses(ASTNode cuu, List<TypeDeclaration> types) {
        cuu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                types.add(node);
                return true;
            }
        });
    }

    private void getMethods(TypeDeclaration td, List<MethodDeclaration> methods) {
        for (MethodDeclaration md : td.getMethods()) {
            methods.add(md);
        }
    }

    private void getSynchronized(TypeDeclaration td, MethodDeclaration md, CompilationUnit cu) {
        String className = td.getName().getIdentifier();
        String methodName = md.getName().getIdentifier();

        // 1. 检测 synchronized 方法
        if (Modifier.isSynchronized(md.getModifiers())) {
            CriticalSectionInfo info = new CriticalSectionInfo();
            info.className = className;
            info.methodName = methodName;
            info.syncType = "synchronized_method";
            boolean isStatic = Modifier.isStatic(md.getModifiers());
            info.monitorObject = isStatic ? className + ".class" : "this";
            info.startLine = cu.getLineNumber(md.getStartPosition());
            info.endLine = cu.getLineNumber(md.getStartPosition() + md.getLength());
            if (md.getBody() != null) {
                extractCriticalSectionFeatures(md.getBody(), info);
            }
            
            //System.out.println("检测到 synchronized 方法:");
            //System.out.println("方法完整内容:");
            //System.out.println(md.toString());
            //System.out.println(info);
            
        }

        // 2. 检测 synchronized 块
        md.accept(new ASTVisitor() {
            @Override
            public boolean visit(SynchronizedStatement node) {
                CriticalSectionInfo info = new CriticalSectionInfo();
                info.className = className;
                info.methodName = methodName;
                info.syncType = "synchronized_block";
                info.monitorObject = node.getExpression().toString();
                info.startLine = cu.getLineNumber(node.getStartPosition());
                info.endLine = cu.getLineNumber(node.getStartPosition() + node.getLength());
                extractCriticalSectionFeatures(node.getBody(), info);
                //System.out.println("检测到 synchronized 块:");
                //System.out.println("同步块完整内容:");
                //System.out.println(node.toString());
                //System.out.println(info);
                return true;
            }
        });
    }
    
    // 放在 initPatternMap 之后
    private void applyMethodRefactoring(TypeDeclaration td, MethodDeclaration md,
            String pattern, CompilationUnit cu,
            int line, Map<String, CriticalSectionModel> modelMap) { 
    	System.out.println("[准备重构同步方法] 类: " 
    	        + td.getName().getIdentifier()
    	        + " 方法: " + md.getName().getIdentifier()
    	        + " 行号: " + line
    	        + " 重构模式: " + pattern);
    	RefactoringPattern rp = patternMap.get(pattern);
            if (rp != null) {
                if ("read_write_lock".equals(pattern)) {
                    // 通过 类名.方法名:行号 查找模型
                    String key = td.getName().getIdentifier() + "." 
                                + md.getName().getIdentifier() + ":" + line;
                    CriticalSectionModel model = modelMap.get(key);
                    // 传入模型（可能为 null，此时子类会使用默认写锁）
                    rp.refactorMethod(td, md, cu, model);
                } else {
                    rp.refactorMethod(td, md, cu);
                }
            }}

    private void applyBlockRefactoring(TypeDeclaration td, MethodDeclaration md,
            SynchronizedStatement syncStmt, String pattern,
            CompilationUnit cu, int line,
            Map<String, CriticalSectionModel> modelMap) {
    	System.out.println("[准备重构同步块] 类: "
    	        + td.getName().getIdentifier()
    	        + " 方法: " + md.getName().getIdentifier()
    	        + " 行号: " + line
    	        + " 锁对象: " + syncStmt.getExpression()
    	        + " 重构模式: " + pattern);


        RefactoringPattern rp = patternMap.get(pattern);
        if (rp != null) {
            if ("read_write_lock".equals(pattern)) {
                String key = td.getName().getIdentifier() + "." 
                            + md.getName().getIdentifier() + ":" + line;
                CriticalSectionModel model = modelMap.get(key);
                rp.refactorBlock(td, md, syncStmt, cu, model);
            } else {
                rp.refactorBlock(td, md, syncStmt, cu);
            }
        }
    }

    private void extractCriticalSectionFeatures(ASTNode body, CriticalSectionInfo info) {
        body.accept(new ASTVisitor() {

            @Override
            public boolean visit(Assignment node) {
                String left = node.getLeftHandSide().toString();
                addIfAbsent(info.writeSet, left);
                addIfAbsent(info.simpleOperations, node.toString());
                collectReadNames(node.getRightHandSide(), info);
                return true;
            }

            @Override
            public boolean visit(PrefixExpression node) {
                PrefixExpression.Operator op = node.getOperator();
                if (op == PrefixExpression.Operator.INCREMENT || op == PrefixExpression.Operator.DECREMENT) {
                    String var = node.getOperand().toString();
                    addIfAbsent(info.writeSet, var);
                    addIfAbsent(info.simpleOperations, node.toString());
                }
                return true;
            }

            @Override
            public boolean visit(PostfixExpression node) {
                PostfixExpression.Operator op = node.getOperator();
                if (op == PostfixExpression.Operator.INCREMENT || op == PostfixExpression.Operator.DECREMENT) {
                    String var = node.getOperand().toString();
                    addIfAbsent(info.writeSet, var);
                    addIfAbsent(info.simpleOperations, node.toString());
                }
                return true;
            }

            @Override
            public boolean visit(IfStatement node) {
                String condition = node.getExpression().toString();
                addIfAbsent(info.ifConditions, condition);
                collectReadNames(node.getExpression(), info);
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                String call = node.toString();
                String name = node.getName().getIdentifier();
                addIfAbsent(info.methodCalls, call);
                if (isCollectionAccess(name)) {
                    addIfAbsent(info.collectionAccesses, call);
                }
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                String name = node.getIdentifier();
                if (isMethodInvocationName(node)) return true;
                if (!isKeywordLikeName(name)) {
                    addIfAbsent(info.readSet, name);
                }
                return true;
            }
        });

    }

    private void collectReadNames(ASTNode node, CriticalSectionInfo info) {
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                String n = name.getIdentifier();
                if (!isKeywordLikeName(n)) {
                    addIfAbsent(info.readSet, n);
                }
                return true;
            }
        });
    }

    private boolean isCollectionAccess(String methodName) {
        return methodName.equals("get") || methodName.equals("put") || methodName.equals("add")
                || methodName.equals("remove") || methodName.equals("contains")
                || methodName.equals("containsKey") || methodName.equals("containsValue")
                || methodName.equals("size") || methodName.equals("clear")
                || methodName.equals("addElement") || methodName.equals("elementAt")
                || methodName.equals("push") || methodName.equals("pop")
                || methodName.equals("offer") || methodName.equals("poll");
    }

    private boolean isMethodInvocationName(SimpleName node) {
        ASTNode parent = node.getParent();
        if (parent instanceof MethodInvocation) {
            MethodInvocation mi = (MethodInvocation) parent;
            return mi.getName() == node;
        }
        return false;
    }

    

    
    private void addIfAbsent(List<String> list, String value) {
        if (value == null) return;
        if (!list.contains(value)) list.add(value);
    }

    private boolean isKeywordLikeName(String name) {
        if (name == null) return true;
        return name.equals("true") || name.equals("false") || name.equals("null")
                || name.equals("this") || name.equals("super") || name.equals("String")
                || name.equals("Date") || name.equals("BigDecimal") || name.equals("Integer")
                || name.equals("Long") || name.equals("Double") || name.equals("Float")
                || name.equals("Boolean") || name.equals("System") || name.equals("Math")
                || name.equals("Logger") || name.equals("Level") || name.equals("IOException")
                || name.equals("Exception") || name.equals("InterruptedException");
    }

    private void findAllCompilationUnits(IJavaProject project) {
        try {
            if (project instanceof IJavaProject) {
                IJavaProject ip = project.getJavaProject();
                for (IJavaElement element : ip.getChildren()) {
                    if (element instanceof IPackageFragmentRoot) {
                        IPackageFragmentRoot root = (IPackageFragmentRoot) element;
                        for (IJavaElement ele : root.getChildren()) {
                            if (ele instanceof IPackageFragment) {
                                IPackageFragment fragment = (IPackageFragment) ele;
                                for (ICompilationUnit unit : fragment.getCompilationUnits()) {
                                    compilationUnits.add(unit);
                                }
                            }
                        }
                    }
                }
            } else if (project instanceof IPackageFragmentRoot) {
                IPackageFragmentRoot root = (IPackageFragmentRoot) project;
                for (IJavaElement ele : root.getChildren()) {
                    if (ele instanceof IPackageFragment) {
                        IPackageFragment fragment = (IPackageFragment) ele;
                        for (ICompilationUnit unit : fragment.getCompilationUnits()) {
                            compilationUnits.add(unit);
                        }
                    }
                }
            } else if (project instanceof IPackageFragment) {
                IPackageFragment fragment = (IPackageFragment) project;
                for (ICompilationUnit unit : fragment.getCompilationUnits()) {
                    compilationUnits.add(unit);
                }
            } else if (project instanceof ICompilationUnit) {
                ICompilationUnit unit = (ICompilationUnit) project;
                compilationUnits.add(unit);
            }
        } catch (JavaModelException e) {
            e.printStackTrace();
        }
    }
}
