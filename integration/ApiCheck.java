import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApiCheck {

	record Info(String name, String superName, List<String> interfaces, boolean isInterface, Set<String> methods, Set<String> fields) {}

	static Map<String, Info> index(Path jar) throws Exception {
		Map<String, Info> classes = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF")) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					ClassModel model = ClassFile.of().parse(in.readAllBytes());
					Set<String> methods = new HashSet<>(), fields = new HashSet<>();
					model.methods().forEach(m -> methods.add(m.methodName().stringValue() + m.methodType().stringValue()));
					model.fields().forEach(f -> fields.add(f.fieldName().stringValue()));
					List<String> interfaces = new ArrayList<>();
					model.interfaces().forEach(i -> interfaces.add(i.asInternalName()));
					classes.put(model.thisClass().asInternalName(), new Info(model.thisClass().asInternalName(), model.superclass().map(ClassEntry::asInternalName).orElse(null), interfaces, model.flags().has(java.lang.reflect.AccessFlag.INTERFACE), methods, fields));
				}
			}
		}
		return classes;
	}

	static boolean hasMethod(Map<String, Info> api, String owner, String key, Set<String> seen) {
		Info info = api.get(owner);
		if (info == null || !seen.add(owner)) return false;
		if (info.methods().contains(key)) return true;
		if (info.superName() != null && hasMethod(api, info.superName(), key, seen)) return true;
		for (String parent : info.interfaces()) if (hasMethod(api, parent, key, seen)) return true;
		return false;
	}

	static boolean hasField(Map<String, Info> api, String owner, String name, Set<String> seen) {
		Info info = api.get(owner);
		if (info == null || !seen.add(owner)) return false;
		if (info.fields().contains(name)) return true;
		if (info.superName() != null && hasField(api, info.superName(), name, seen)) return true;
		for (String parent : info.interfaces()) if (hasField(api, parent, name, seen)) return true;
		return false;
	}

	static final Set<String> INHERITED = Set.of("name()Ljava/lang/String;", "ordinal()I", "hashCode()I", "equals(Ljava/lang/Object;)Z", "toString()Ljava/lang/String;", "getClass()Ljava/lang/Class;");

	public static void main(String[] args) throws Exception {
		Path plugin = Path.of(args[0]);
		Set<String> refsClasses = new TreeSet<>(), refsMethods = new TreeSet<>(), refsFields = new TreeSet<>();
		Map<String, Boolean> interfaceCalls = new HashMap<>();
		try (ZipFile zip = new ZipFile(plugin.toFile())) {
			for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
				if (!entry.getName().startsWith("me/itemstacker/") || entry.getName().startsWith("me/itemstacker/libs/") || !entry.getName().endsWith(".class")) continue;
				ClassModel model = ClassFile.of().parse(zip.getInputStream(entry).readAllBytes());
				for (PoolEntry pool : model.constantPool()) {
					if (pool instanceof ClassEntry c) {
						String name = c.asInternalName();
						if (name.startsWith("org/bukkit/") || name.startsWith("io/papermc/")) refsClasses.add(name);
					} else if (pool instanceof MemberRefEntry member) {
						String owner = member.owner().asInternalName();
						if (!owner.startsWith("org/bukkit/") && !owner.startsWith("io/papermc/")) continue;
						if (member instanceof FieldRefEntry) refsFields.add(owner + "#" + member.name().stringValue());
						else {
							refsMethods.add(owner + "#" + member.name().stringValue() + member.type().stringValue());
							interfaceCalls.put(owner, member instanceof InterfaceMethodRefEntry);
						}
					}
				}
			}
		}
		int problems = 0;
		for (int i = 1; i < args.length; i++) {
			Path jar = Path.of(args[i]);
			Map<String, Info> api = index(jar);
			List<String> found = new ArrayList<>();
			for (String c : refsClasses) if (!api.containsKey(c)) found.add("missing class " + c);
			for (String m : refsMethods) {
				int hash = m.indexOf('#');
				String owner = m.substring(0, hash), key = m.substring(hash + 1);
				Info info = api.get(owner);
				if (info == null) {
					found.add("missing class " + owner + " for " + key);
					continue;
				}
				if (!hasMethod(api, owner, key, new HashSet<>()) && !key.startsWith("<init>") && !INHERITED.contains(key)) found.add("missing method " + owner + "." + key);
				if (key.startsWith("<init>") && !info.methods().contains(key)) found.add("missing constructor " + owner + "." + key);
				if (interfaceCalls.get(owner) != null && interfaceCalls.get(owner) != info.isInterface()) found.add("class/interface mismatch " + owner);
			}
			for (String f : refsFields) {
				int hash = f.indexOf('#');
				if (!hasField(api, f.substring(0, hash), f.substring(hash + 1), new HashSet<>())) found.add("missing field " + f);
			}
			System.out.println(jar.getFileName() + ": " + (found.isEmpty() ? "OK (" + refsMethods.size() + " methods, " + refsFields.size() + " fields, " + refsClasses.size() + " classes checked)" : found.size() + " problem(s)"));
			for (String line : found) System.out.println("   " + line);
			problems += found.size();
		}
		System.exit(problems == 0 ? 0 : 1);
	}
}
