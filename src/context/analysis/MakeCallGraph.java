	package context.analysis;
	
	import java.io.File;
	import java.io.IOException;
	import java.util.ArrayList;
	import java.util.Collection;
	import java.util.HashMap;
	import java.util.List;
	import java.util.Map;
	
	import org.eclipse.core.runtime.IPath;
	
	import com.ibm.wala.classLoader.BinaryDirectoryTreeModule;
	import com.ibm.wala.classLoader.IClass;
	import com.ibm.wala.classLoader.IMethod;
	import com.ibm.wala.classLoader.Language;
	import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
	import com.ibm.wala.ipa.callgraph.AnalysisOptions;
	import com.ibm.wala.ipa.callgraph.AnalysisScope;
	import com.ibm.wala.ipa.callgraph.CallGraph;
	import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
	import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
	import com.ibm.wala.ipa.callgraph.Entrypoint;
	import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
	import com.ibm.wala.ipa.callgraph.impl.Util;
	import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
	import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
	import com.ibm.wala.ipa.cha.ClassHierarchy;
	import com.ibm.wala.ipa.cha.ClassHierarchyException;
	import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
	import com.ibm.wala.ipa.slicer.SDG;
	import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
	import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
	import com.ibm.wala.ipa.slicer.Statement;
	import com.ibm.wala.types.ClassLoaderReference;
	import com.ibm.wala.types.TypeName;
	import com.ibm.wala.util.config.AnalysisScopeReader;
	import com.ibm.wala.util.graph.Graph;
	import com.ibm.wala.util.io.FileProvider;
	import com.ibm.wala.util.strings.Atom;
	
	public class MakeCallGraph {
	
		public IPath filename;
		public CallGraph cg;
		public ClassHierarchy cha;
		public Graph<Statement> sdg;
		public AnalysisScope scope;
		public PointerAnalysis<InstanceKey> pointerAnalysis;
	
		public Map<TypeName, ArrayList<TypeName>> mapTemp = new HashMap<TypeName, ArrayList<TypeName>>();
	
		public MakeCallGraph(IPath filename)
				throws IOException, ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException {
	
			ClassLoader javaLoader = MakeCallGraph.class.getClassLoader();
			AnalysisScope scope = AnalysisScopeReader.readJavaScope("primordial.txt",
					new FileProvider().getFile("Java60RegressionExclusions.txt"), javaLoader);
			
			ClassLoaderReference clr = scope.getLoader(Atom.findOrCreateUnicodeAtom("Application"));
	
			// 添加应用类目录（可以是 bin 目录，如果需要可添加多个）
			File file = new FileProvider().getFile(filename.toString(), javaLoader);
			scope.addToScope(clr, new BinaryDirectoryTreeModule(file));
	
			// 如果有其他输出目录，可在此添加（例如 target/classes）
			// 如果你知道项目中还有其他 class 输出目录，取消注释并修改路径
			// File parent = file.getParentFile();
			// for (String dir : new String[]{"target/classes", "build/classes",
			// "out/production"}) {
			// File extra = new File(parent, dir);
			// if (extra.exists() && extra.isDirectory() && !extra.equals(file)) {
			// scope.addToScope(clr, new BinaryDirectoryTreeModule(extra));
			// }
			// }
	
			this.scope = scope;
			cha = ClassHierarchyFactory.make(scope);
	
			// 打印应用类数量，用于调试
			int appClassCount = countApplicationClasses(cha, scope);
			System.out.println("===== WALA 应用类数量: " + appClassCount + " =====");
	
			// ----- 手动构建入口点：使用所有应用类的非抽象、非构造、非 native 方法 -----
			List<Entrypoint> epList = new ArrayList<>();
			for (IClass klass : cha) {
				if (!scope.isApplicationLoader(klass.getClassLoader()))
					continue;
				for (IMethod method : klass.getDeclaredMethods()) {
					// 跳过抽象方法、构造器<init>、静态初始化<clinit>、native 方法
					if (method.isAbstract() || method.isInit() || method.isClinit() || method.isNative()) {
						continue;
					}
					epList.add(new DefaultEntrypoint(method, cha));
				}
			}
			Iterable<Entrypoint> entrypoints = epList;
			// ----------------------------------------------------------------
	
			AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
			CallGraphBuilder<InstanceKey> builder = Util.makeVanillaZeroOneCFABuilder(
				    Language.JAVA, options, new AnalysisCacheImpl(), cha, scope);
			//CallGraphBuilder<InstanceKey> builder = Util.makeZeroCFABuilder(
			//             Language.JAVA, options, new AnalysisCacheImpl(), cha, scope);
			cg = builder.makeCallGraph(options, null);
	
			final PointerAnalysis<InstanceKey> pointerAnalysis = builder.getPointerAnalysis();
			this.pointerAnalysis = pointerAnalysis;
			sdg = new SDG<>(cg, pointerAnalysis, DataDependenceOptions.NO_BASE_NO_HEAP_NO_EXCEPTIONS,
					ControlDependenceOptions.NONE);
	
		}
	
		// 辅助方法：统计应用类数量
		private int countApplicationClasses(ClassHierarchy cha, AnalysisScope scope) {
			int count = 0;
			for (IClass c : cha) {
				if (scope.isApplicationLoader(c.getClassLoader()))
					count++;
			}
			return count;
		}
	
		// ===== 以下方法不变 =====
		public Map<TypeName, ArrayList<TypeName>> getCHAMap() {
			// Extends
			for (IClass c : cha) {
				if (scope.isApplicationLoader(c.getClassLoader())) {
					Collection<IClass> collection = cha.getImmediateSubclasses(c);
					if (collection.size() > 0) {
						ArrayList<TypeName> listTemp = new ArrayList<TypeName>();
						for (IClass cTemp : collection) {
							listTemp.add(cTemp.getName());
						}
						mapTemp.put(c.getName(), listTemp);
					} else {
						ArrayList<TypeName> listTemp = new ArrayList<TypeName>();
						mapTemp.put(c.getName(), listTemp);
					}
				}
			}
			// Implements
			for (IClass c : cha) {
				if (scope.isApplicationLoader(c.getClassLoader())) {
					Collection<IClass> collection = c.getAllImplementedInterfaces();
					for (IClass cTemp : collection) {
						if (mapTemp.containsKey(cTemp.getName())) {
							mapTemp.get(cTemp.getName()).add(c.getName());
						} else {
							ArrayList<TypeName> listTemp = new ArrayList<TypeName>();
							listTemp.add(c.getName());
							mapTemp.put(cTemp.getName(), listTemp);
						}
					}
				}
			}
			return mapTemp;
		}
	
		public void dealExtends() {
			for (Map.Entry<TypeName, ArrayList<TypeName>> entry : mapTemp.entrySet()) {
				ArrayList<TypeName> listType = entry.getValue();
				ArrayList<TypeName> listAddType = new ArrayList<TypeName>();
				dealExtendIterator(listType, listAddType);
			}
		}
	
		private void dealExtendIterator(ArrayList<TypeName> listType, ArrayList<TypeName> listAddType) {
			ArrayList<TypeName> listAddTypeTemp = new ArrayList<TypeName>();
			for (TypeName tn : listType) {
				if (mapTemp.containsKey(tn)) {
					listAddType.addAll(mapTemp.get(tn));
				}
			}
			if (listAddType.size() == 0) {
				return;
			}
			dealExtendIterator(listAddType, listAddTypeTemp);
			listType.addAll(listAddType);
		}
	}