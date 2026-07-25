package context.analysis;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.util.intset.OrdinalSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WALA 1.5.6 兼容版 — 在 IR 中定位同步指令并提取与 AST 对应的信息
 */
public class SynchronizedAnalysis {

    private CallGraph cg;
    private PointerAnalysis<InstanceKey> pointerAnalysis;

    public SynchronizedAnalysis(CallGraph cg, PointerAnalysis<InstanceKey> pointerAnalysis) {
        this.cg = cg;
        this.pointerAnalysis = pointerAnalysis;
    }

    /**
     * 查找指定方法中行号对应的 monitor 指令
     * @param typeName   WALA 内部类型名，例如 "Lcom/example/MyClass"
     * @param methodName 方法名
     * @param lineNumbers AST 端收集的行号列表（synchronized 块起始行 + 方法起始行）
     * @return 匹配到的 MonitorInfo 列表
     */
    
	public List<MonitorInfo> findMonitors(String typeName, String methodName, int expectedParamCount,
			List<Integer> lineNumbers) {
		List<MonitorInfo> result = new ArrayList<>();

// 用于去重同步方法：key = 类名:方法名:参数个数:是否静态
		Set<String> processedSyncMethods = new HashSet<>();

		for (CGNode cgNode : cg) {
// 1. 类型名匹配
			if (!cgNode.getMethod().getDeclaringClass().getName().toString().equals(typeName))
				continue;
// 2. 方法名匹配
			if (!cgNode.getMethod().getName().toString().equals(methodName))
				continue;
// 3. 参数个数匹配（防止重载误匹配）
			if (cgNode.getMethod().getNumberOfParameters() != expectedParamCount)
				continue;

			SSAInstruction[] instructions = cgNode.getIR().getInstructions();
			SymbolTable symTab = cgNode.getIR().getSymbolTable();
			IBytecodeMethod<?> ibm = (IBytecodeMethod<?>) cgNode.getIR().getMethod();

// ==================== 同步方法处理 ====================
			if (cgNode.getMethod().isSynchronized()) {
// 过滤桥接方法和合成方法（这些在 AST 中没有对应节点）
				if (cgNode.getMethod().isBridge() || cgNode.getMethod().isSynthetic()) {
					continue;
				}
// 去重：每个 AST 同步方法只匹配一个 WALA 同步信息
				String key = typeName + ":" + methodName + ":" + expectedParamCount + ":"
						+ cgNode.getMethod().isStatic();
				if (!processedSyncMethods.add(key)) {
					continue; // 已经处理过，跳过
				}
// 生成同步方法的信息
				MonitorInfo info = new MonitorInfo();
				info.lineNumber = lineNumbers.isEmpty() ? -1 : lineNumbers.get(0);
				info.isEnter = true;
				info.lockSSAValue = -1;
				info.lockDescription = cgNode.getMethod().isStatic() ? "class (synchronized method)"
						: "this (synchronized method)";
				info.cgNode = cgNode;
				result.add(info);
			}

// ==================== 同步块处理 ====================
			for (int i = 0; i < instructions.length; i++) {
				SSAInstruction si = instructions[i];
				if (!(si instanceof SSAMonitorInstruction))
					continue;

				SSAMonitorInstruction monitor = (SSAMonitorInstruction) si;
				if (!monitor.isMonitorEnter())
					continue;

				int bytecodeIndex;
				try {
					bytecodeIndex = ibm.getBytecodeIndex(monitor.iIndex());
				} catch (InvalidClassFileException e) {
					e.printStackTrace();
					continue;
				}
				int lineNumber = cgNode.getIR().getMethod().getLineNumber(bytecodeIndex);

// 行号匹配（容差±1）
				if (lineNumbers.contains(lineNumber) || lineNumbers.contains(lineNumber - 1)
						|| lineNumbers.contains(lineNumber + 1)) {
					MonitorInfo info = new MonitorInfo();
					info.lineNumber = lineNumber;
					info.isEnter = true;
					info.lockSSAValue = monitor.getRef();
					info.lockDescription = describeLock(symTab, info.lockSSAValue, cgNode);
					info.cgNode = cgNode;
					result.add(info);
				}
			}
		}
		return result;
	}
    /**
     * 描述锁对象：利用 SymbolTable 和指针分析
     */
    private String describeLock(SymbolTable symTab, int valueNumber, CGNode cgNode) {
        // 1. 常量
        if (symTab.isConstant(valueNumber)) {
            return "constant: " + symTab.getConstantValue(valueNumber);
        }
        // 2. 判断是否为 this (WALA 实例方法的第一个参数 valueNumber=1)
        int paramCount = cgNode.getMethod().getNumberOfParameters();
        if (valueNumber >= 1 && valueNumber <= paramCount) {
            if (valueNumber == 1 && !cgNode.getMethod().isStatic()) {
                return "this (parameter 1)";
            }
            return "parameter#" + valueNumber;
        }
        // 3. 指针分析（获取可能的类型）
        if (pointerAnalysis != null) {
            LocalPointerKey pkey = new LocalPointerKey(cgNode, valueNumber);
            OrdinalSet<InstanceKey> pts = pointerAnalysis.getPointsToSet(pkey);
            if (pts != null && !pts.isEmpty()) {
                StringBuilder sb = new StringBuilder("points-to: ");
                for (InstanceKey ik : pts) {
                    sb.append(ik.getConcreteType().getName().toString()).append(" ");
                }
                return sb.toString().trim();
            }
        }
        return "ssa#" + valueNumber;
    }

    public static class MonitorInfo {
        public int lineNumber;
        public boolean isEnter;          // true=monitorenter, false=monitorexit
        public int lockSSAValue;         // 锁对象的 SSA 值编号
        public String lockDescription;   // 锁对象的描述
        public CGNode cgNode;

        @Override
        public String toString() {
            String type = isEnter ? "monitorenter" : "monitorexit";
            return String.format("[WALA] Line %d: %s, lock = %s (ssa#%d)",
                lineNumber, type, lockDescription, lockSSAValue);
        }
    }
}