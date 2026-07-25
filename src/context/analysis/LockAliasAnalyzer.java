package context.analysis;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.LocalPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.util.intset.OrdinalSet;

/**
 * 锁对象的别名分析工具。
 * 基于 WALA 指针分析，判断不同同步点使用的锁对象是否可能为同一对象（别名）。
 */
public class LockAliasAnalyzer {

    private final PointerAnalysis<InstanceKey> pointerAnalysis;

    public LockAliasAnalyzer(PointerAnalysis<InstanceKey> pointerAnalysis) {
        this.pointerAnalysis = pointerAnalysis;
    }

    /**
     * 获取某个 SSA 值编号可能指向的对象集合（points-to set）。
     * @param cgNode      方法对应的 CGNode
     * @param valueNumber SSA 值编号（在 SynchronizedAnalysis.MonitorInfo.lockSSAValue 中）
     * @return 可能指向的 InstanceKey 集合，若无法分析则返回空集
     */
    public OrdinalSet<InstanceKey> getPointsToSet(CGNode cgNode, int valueNumber) {
        if (valueNumber == -1) {
            // 同步方法：valueNumber == -1 表示 this（实例方法）或 class（静态方法）
            // 对于实例方法，'this' 对应 valueNumber = 1（WALA 约定）
            // 对于静态方法，没有 this，但锁是 Class 对象，可用指针分析获取
            if (cgNode.getMethod().isStatic()) {
                // 静态同步方法的锁对象可通过查找类对象分配获得
                // 这里我们返回空集，由外部通过 isSynchronizedMethod() 判断
                return OrdinalSet.empty();
            } else {
                // 实例同步方法：用 valueNumber = 1 来获取 this 的 points-to 集
                return getPointsToSet(cgNode, 1);
            }
        }
        LocalPointerKey pKey = new LocalPointerKey(cgNode, valueNumber);
        return pointerAnalysis.getPointsToSet(pKey);
    }

    /**
     * 判断两个锁对象表达式是否可能别名（指向同一堆对象）。
     * @param node1 第一个锁所在方法的 CGNode
     * @param vn1   第一个锁的 SSA 值编号
     * @param node2 第二个锁所在方法的 CGNode
     * @param vn2   第二个锁的 SSA 值编号
     * @return true 如果两个表达式可能指向同一对象
     */
    public boolean mayAlias(CGNode node1, int vn1, CGNode node2, int vn2) {
        // 处理同步方法情况（vn == -1）
        if (vn1 == -1 && vn2 == -1) {
            // 两个都是同步方法，直接比较是否为同一个类中的同步方法？
            // 但我们需要更精确：如果两个方法是同一个对象的实例方法，则可能别名
            // 简化处理：如果声明类相同，且都是实例同步方法，则可能别名（this 可能相同）
            // 更精确应通过指针分析比较 this 参数
            if (node1.getMethod().isStatic() || node2.getMethod().isStatic()) {
                // 静态同步方法锁是 Class 对象，同一类的所有静态同步方法锁相同
                return node1.getMethod().getDeclaringClass().equals(node2.getMethod().getDeclaringClass());
            } else {
                // 实例同步方法：比较 this 的 points-to 集
                OrdinalSet<InstanceKey> pts1 = getPointsToSet(node1, 1);
                OrdinalSet<InstanceKey> pts2 = getPointsToSet(node2, 1);
                return pts1 != null && pts2 != null && !pts1.isEmpty() && !pts2.isEmpty()
                        && pts1.containsAny(pts2);
            }
        }

        // 至少有一个是同步块
        if (vn1 == -1) {
            // vn1 是同步方法，将 vn1 替换为 1（this）并递归
            return mayAlias(node1, 1, node2, vn2);
        }
        if (vn2 == -1) {
            return mayAlias(node1, vn1, node2, 1);
        }

        // 两个都是同步块，正常比较 points-to 集
        OrdinalSet<InstanceKey> pts1 = getPointsToSet(node1, vn1);
        OrdinalSet<InstanceKey> pts2 = getPointsToSet(node2, vn2);
        if (pts1 == null || pts2 == null) return false;
        return pts1.containsAny(pts2);
    }

    /**
     * 判断两个 MonitorInfo 是否可能使用同一锁对象（别名）。
     */
    public boolean mayAlias(SynchronizedAnalysis.MonitorInfo m1,
                            SynchronizedAnalysis.MonitorInfo m2) {
        return mayAlias(m1.cgNode, m1.lockSSAValue, m2.cgNode, m2.lockSSAValue);
    }

    /**
     * 获取锁对象的简要描述（用于调试/日志），复用 SynchronizedAnalysis 的描述方式。
     * 实际项目中可将其提取为公共方法。
     */
    public String describeLock(CGNode cgNode, int valueNumber) {
        if (valueNumber == -1) {
            if (cgNode.getMethod().isStatic()) {
                return "class (synchronized method)";
            } else {
                return "this (synchronized method)";
            }
        }
        // 可复用 SynchronizedAnalysis 中的 describeLock
        // 但为了避免重复，这里直接调用指针分析
        OrdinalSet<InstanceKey> pts = getPointsToSet(cgNode, valueNumber);
        if (pts != null && !pts.isEmpty()) {
            StringBuilder sb = new StringBuilder("points-to: ");
            for (InstanceKey ik : pts) {
                sb.append(ik.getConcreteType().getName().toString()).append(" ");
            }
            return sb.toString().trim();
        }
        return "ssa#" + valueNumber;
    }
}