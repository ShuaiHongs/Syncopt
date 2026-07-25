package context.refactoring.pattern;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import context.refactoring.CriticalSectionModel;  // 确保导入

public abstract class RefactoringPattern {

    // ========== 原有抽象方法（保持不变） ==========
    public abstract boolean refactorMethod(TypeDeclaration td, MethodDeclaration md, CompilationUnit cu);

    public abstract boolean refactorBlock(TypeDeclaration td, MethodDeclaration md,
                                          SynchronizedStatement syncStmt, CompilationUnit cu);

    // ========== 新增：带模型参数的重载版本（默认调用无模型版本） ==========
    public boolean refactorMethod(TypeDeclaration td, MethodDeclaration md, CompilationUnit cu, CriticalSectionModel model) {
        return refactorMethod(td, md, cu); // 默认调用无模型版本
    }

    public boolean refactorBlock(TypeDeclaration td, MethodDeclaration md,
                                  SynchronizedStatement syncStmt, CompilationUnit cu, CriticalSectionModel model) {
        return refactorBlock(td, md, syncStmt, cu); // 默认调用无模型版本
    }

    public String getPatternName() {
        return this.getClass().getSimpleName();
    }
}