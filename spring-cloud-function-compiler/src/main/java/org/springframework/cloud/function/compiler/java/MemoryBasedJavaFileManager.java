/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.compiler.java;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.function.compiler.java.IterableClasspath.ZipEnumerator;

/**
 * A file manager that serves source code from in memory and ensures output results are
 * kept in memory rather than being flushed out to disk. The JavaFileManager is also used
 * as a lookup mechanism for resolving types.
 *
 * @author Andy Clement
 * @author Oleg Zhurakousky
 */
public class MemoryBasedJavaFileManager implements JavaFileManager {

	final static String BOOT_PACKAGING_PREFIX_FOR_CLASSES = "BOOT-INF/classes/";

	final static String BOOT_PACKAGING_PREFIX_FOR_LIBRARIES = "BOOT-INF/lib/";
	
	private static Logger logger = LoggerFactory
			.getLogger(MemoryBasedJavaFileManager.class);

	private CompilationOutputCollector outputCollector;

//	private List<CloseableFilterableJavaFileObjectIterable> toClose = new ArrayList<>();

	private Map<String, File> resolvedAdditionalDependencies = new LinkedHashMap<>();
	
	private String platformClasspath;

	private String classpath;

	private CompilationInfoCache compilationInfoCache;
	
	private Map<Key, IterableClasspath> iterables = new HashMap<>();
	
	public MemoryBasedJavaFileManager() {
		outputCollector = new CompilationOutputCollector();
		compilationInfoCache = new CompilationInfoCache();
	}

	@Override
	public int isSupportedOption(String option) {
		logger.debug("isSupportedOption({})", option);
		return -1; // Not yet supporting options
	}

	@Override
	public ClassLoader getClassLoader(Location location) {
		// Do not simply return the context classloader as it may get closed and then
		// be unusable for loading any further classes
		logger.debug("getClassLoader({})", location);
		return null; // Do not currently need to load plugins
	}
	
	// Holds information that may help speed up compilation
	static class CompilationInfoCache {

		private Map<File, ArchiveInfo> archivePackageCache;
		
		static class ArchiveInfo {
			
			// The packages identified in a particular archive
			private List<String> packageNames;
			
			private boolean isBootJar = false;
			
			public ArchiveInfo(List<String> packageNames, boolean isBootJar) {
				this.packageNames = packageNames;
				Collections.sort(this.packageNames);
				this.isBootJar = isBootJar;
			}

			public List<String> getPackageNames() {
				return packageNames;
			}

			public boolean isBootJar() {
				return isBootJar;
			}

			public boolean containsPackage(String packageName, boolean subpackageMatchesAllowed) {
				if (subpackageMatchesAllowed) {
					for (String candidatePackageName: packageNames) {
						if (candidatePackageName.startsWith(packageName)) {
							return true;
						}
					}
					return false;
				} else {
					// Must be an exact match, fast binary search:
					int pos = Collections.binarySearch(packageNames, packageName);
					return  (pos >= 0);
				}
			}
		}
		
		ArchiveInfo getArchiveInfoFor(File archive) {
			if (!archive.isFile() || !(archive.getName().endsWith(".zip") || archive.getName().endsWith(".jar"))) {
				// it is not an archive
				return null;
			}
			if (archivePackageCache == null) {
				archivePackageCache = new HashMap<>();
			}
			try {
				ArchiveInfo result = archivePackageCache.get(archive);
				if (result == null) {
					result = buildArchiveInfo(archive);
					archivePackageCache.put(archive, result);
				}
				return result;
			} catch (Exception e) {
				throw new IllegalStateException("Unexpected problem caching entries from "+archive.getName(), e);
			}
		}
		
		/**
		 * Walk the specified archive and collect up the package names of any .class files encountered. If
		 * the archive contains nested jars packaged in a BOOT style way (under a BOOT-INF/lib folder) then
		 * walk those too and include relevant packages.
		 * 
		 * @param file archive file to discover packages from
		 * @return an ArchiveInfo encapsulating package info from the archive
		 */
		private ArchiveInfo buildArchiveInfo(File file) {
			List<String> packageNames = new ArrayList<>();
			boolean isBootJar = false;
			try (ZipFile openArchive = new ZipFile(file)) {
				Enumeration<? extends ZipEntry> entries = openArchive.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName();
					if (name.endsWith(".class")) {
						if (name.startsWith(BOOT_PACKAGING_PREFIX_FOR_CLASSES)) {
							isBootJar = true;
							int idx = name.lastIndexOf('/') + 1;
							if (idx != 0 ) {
								if (idx == BOOT_PACKAGING_PREFIX_FOR_CLASSES.length()) {
									// default package
									packageNames.add("/");
								} else {
									// Normalize to forward slashes
									name = name.substring(BOOT_PACKAGING_PREFIX_FOR_CLASSES.length(), idx);
									name = name.replace('\\', '/');
									packageNames.add(name);
								}
							}
						} else {
							int idx = name.lastIndexOf('/') + 1;
//							if (name.contains("TestX")) {
//								System.out.println("For TestX: "+name+" "+idx+" "+BOOT_PACKAGING_PREFIX_FOR_CLASSES.length());
//							}
							if (idx != 0 ) {
								// Normalize to forward slashes
								name = name.replace('\\', '/');
								name = name.substring(0, idx);
								packageNames.add(name);
							} else if (idx == 0) {
								// default package entries in here
								packageNames.add("/");
							}
						}
					} else if (name.startsWith(BOOT_PACKAGING_PREFIX_FOR_LIBRARIES) && name.endsWith(".jar")) {
						isBootJar = true;
						try (ZipInputStream zis = new ZipInputStream(openArchive.getInputStream(entry))) {
							Enumeration<? extends ZipEntry> nestedZipEnumerator = new ZipEnumerator(zis);
							while (nestedZipEnumerator.hasMoreElements()) {
								ZipEntry innerEntry = nestedZipEnumerator.nextElement();
								String innerEntryName = innerEntry.getName();
								if (innerEntryName.endsWith(".class")) {
									int idx = innerEntryName.lastIndexOf('/') + 1;
									if (idx != 0 ) {
										// Normalize to forward slashes
										innerEntryName = innerEntryName.replace('\\', '/');
										innerEntryName = innerEntryName.substring(0, idx);
										packageNames.add(innerEntryName);
									} else if (idx == 0) {
										// default package entries in here
										packageNames.add("/");
									}
								}
							}
						}
					}
				}
			} catch (IOException ioe) {
				throw new IllegalStateException("Unexpected problem determining packages in "+file,ioe);
			}
			return new ArchiveInfo(packageNames, isBootJar);
		}

	}

	static class Key {
		private String classpath;
		private String packageName;
		private Set<Kind> kinds;
		private boolean recurse;
		
		public Key(String classpath, String packageName, Set<Kind> kinds, boolean recurse) {
			this.classpath = classpath;
			this.packageName = packageName;
			this.kinds = kinds;
			this.recurse = recurse;
		}
		
		@Override
		public int hashCode() {
			return ((classpath.hashCode()*37+(packageName==null?0:packageName.hashCode()))*37+kinds.hashCode())*37+(recurse?1:0);
		}
	
		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Key)) {
				return false;
			}
			Key that = (Key)obj;
			return classpath.equals(that.classpath) &&
					kinds.equals(that.kinds) &&
					(recurse==that.recurse) &&
					(packageName==null?(that.packageName==null):this.packageName.equals(that.packageName));
		}
	}

	private String getPlatformClassPath() {
		if (platformClasspath == null) {			
			platformClasspath = System.getProperty("sun.boot.class.path");
		}
		return platformClasspath;
	}
	
	@Override
	public Iterable<JavaFileObject> list(Location location, String packageName,
			Set<Kind> kinds, boolean recurse) throws IOException {
		logger.debug("list({},{},{},{})", location, packageName, kinds, recurse);
		String classpath = "";
		if (location == StandardLocation.PLATFORM_CLASS_PATH
				&& (kinds == null || kinds.contains(Kind.CLASS))) {
			classpath = getPlatformClassPath();
			logger.debug("Creating iterable for boot class path: {}", classpath);
		}
		else if (location == StandardLocation.CLASS_PATH
				&& (kinds == null || kinds.contains(Kind.CLASS))) {
			String javaClassPath = getClassPath();
			if (!resolvedAdditionalDependencies.isEmpty()) {
				for (File resolvedAdditionalDependency : resolvedAdditionalDependencies
						.values()) {
					javaClassPath += File.pathSeparatorChar + resolvedAdditionalDependency
							.toURI().toString().substring("file:".length());
				}
			}
			classpath = javaClassPath;
			logger.debug("Creating iterable for class path: {}", classpath);
		}
		Key k = new Key(classpath, packageName, kinds, recurse);
		IterableClasspath resultIterable = iterables.get(k);
		if (resultIterable == null) {
			resultIterable = new IterableClasspath(compilationInfoCache, classpath, packageName, recurse);
			iterables.put(k, resultIterable);
		}
		resultIterable.reset();
		return resultIterable;
	}

	private String getClassPath() {
		if (classpath == null) {
			ClassLoader loader = InMemoryJavaFileObject.class.getClassLoader();
			String cp = null;
			if (loader instanceof URLClassLoader) {
				URL[] urls = ((URLClassLoader) loader).getURLs();
				if (urls.length > 1) { // heuristic that catches Maven surefire tests
					if (!urls[0].toString().startsWith("jar:file:")) { // heuristic for Spring Boot fat jar
						StringBuilder builder = new StringBuilder();
						for (URL url : urls) {
							if (builder.length() > 0) {
								builder.append(File.pathSeparator);
							}
							String path = url.toString();
							if (path.startsWith("file:")) {
								path = path.substring("file:".length());
							}
							builder.append(path);
						}
						cp = builder.toString();
					}
				}
			}
			if (cp == null) {
				cp = System.getProperty("java.class.path");
			}
			classpath = pathWithPlatformClassPathRemoved(cp);
		}
		return classpath;
	}

	// remove the platform classpath entries, they will be search separately (and earlier)
	private String pathWithPlatformClassPathRemoved(String classpath) {
		Set<String> pcps = toList(getPlatformClassPath());
		Set<String> cps = toList(classpath);
		cps.removeAll(pcps);
		StringBuilder builder = new StringBuilder();
		for (String cpe: cps) {
			if (builder.length() > 0) {
				builder.append(File.pathSeparator);
			}
			builder.append(cpe);
		}
		return builder.toString();
	}

	private Set<String> toList(String path) {
		Set<String> result = new LinkedHashSet<>();
		StringTokenizer tokenizer = new StringTokenizer(path,File.pathSeparator);
		while (tokenizer.hasMoreTokens()) {
			result.add(tokenizer.nextToken());
		}
		return result;
	}

	@Override
	public boolean hasLocation(Location location) {
		logger.debug("hasLocation({})", location);
		return (location == StandardLocation.SOURCE_PATH
				|| location == StandardLocation.CLASS_PATH
				|| location == StandardLocation.PLATFORM_CLASS_PATH);
	}

	@Override
	public String inferBinaryName(Location location, JavaFileObject file) {
		if (location == StandardLocation.SOURCE_PATH) {
			return null;
		}
		// Kind of ignoring location here... assuming we want basically the FQ type name
		// Example value from getName(): javax/validation/bootstrap/GenericBootstrap.class
		String classname = file.getName().replace('/', '.').replace('\\', '.');
		return classname.substring(0, classname.lastIndexOf(".class"));
	}

	@Override
	public boolean isSameFile(FileObject a, FileObject b) {
		logger.debug("isSameFile({},{})", a, b);
		return a.equals(b);
	}

	@Override
	public boolean handleOption(String current, Iterator<String> remaining) {
		logger.debug("handleOption({},{})", current, remaining);
		return false; // This file manager does not manage any options
	}

	@Override
	public JavaFileObject getJavaFileForInput(Location location, String className,
			Kind kind) throws IOException {
		logger.debug("getJavaFileForInput({},{},{})", location, className, kind);
		throw new IllegalStateException("Not expected to be used in this context");
	}

	@Override
	public JavaFileObject getJavaFileForOutput(Location location, String className,
			Kind kind, FileObject sibling) throws IOException {
		logger.debug("getJavaFileForOutput({},{},{},{})", location, className, kind,
				sibling);
		// Example parameters: CLASS_OUTPUT, Foo, CLASS,
		// StringBasedJavaSourceFileObject[string:///a/b/c/Foo.java]
		return outputCollector.getJavaFileForOutput(location, className, kind, sibling);
	}

	@Override
	public FileObject getFileForInput(Location location, String packageName,
			String relativeName) throws IOException {
		logger.debug("getFileForInput({},{},{})", location, packageName, relativeName);
		throw new IllegalStateException("Not expected to be used in this context");
	}

	@Override
	public FileObject getFileForOutput(Location location, String packageName,
			String relativeName, FileObject sibling) throws IOException {
		logger.debug("getFileForOutput({},{},{},{})", location, packageName, relativeName,
				sibling);
		// This can be called when the annotation config processor runs
		// Example parameters: CLASS_OUTPUT, ,
		// META-INF/spring-configuration-metadata.json, null
		return outputCollector.getFileForOutput(location, packageName, relativeName,
				sibling);
	}

	@Override
	public void flush() throws IOException {
	}

	@Override
	public void close() throws IOException {
		Collection<IterableClasspath> toClose = iterables.values();
		for (IterableClasspath icp: toClose) {
			icp.close();
		}
	}

	public List<CompiledClassDefinition> getCompiledClasses() {
		return outputCollector.getCompiledClasses();
	}

	public List<CompilationMessage> addAndResolveDependencies(String[] dependencies) {
		List<CompilationMessage> resolutionMessages = new ArrayList<>();
		for (String dependency : dependencies) {
			if (dependency.startsWith("maven:")) {
				// Resolving an explicit external archive
				String coordinates = dependency.replaceFirst("maven:\\/*", "");
				DependencyResolver engine = DependencyResolver.instance();
				try {
					File resolved = engine.resolve(
							new Dependency(new DefaultArtifact(coordinates), "runtime"));
					// Example:
					// dependency =
					// maven://org.springframework:spring-expression:4.3.9.RELEASE
					// resolved.toURI() =
					// file:/Users/aclement/.m2/repository/org/springframework/spring-expression/4.3.9.RELEASE/spring-expression-4.3.9.RELEASE.jar
					resolvedAdditionalDependencies.put(dependency, resolved);
				}
				catch (RuntimeException re) {
					CompilationMessage compilationMessage = new CompilationMessage(
							CompilationMessage.Kind.ERROR, re.getMessage(), null, 0, 0);
					resolutionMessages.add(compilationMessage);
				}
			} else if (dependency.startsWith("file:")) {
				resolvedAdditionalDependencies.put(dependency, new File(URI.create(dependency)));
			}
			else {
				resolutionMessages.add(new CompilationMessage(
						CompilationMessage.Kind.ERROR,
						"Unrecognized dependency: " + dependency
								+ " (expected something of the form: maven://groupId:artifactId:version)",
						null, 0, 0));
			}
		}
		return resolutionMessages;
	}

	public Map<String, File> getResolvedAdditionalDependencies() {
		return resolvedAdditionalDependencies;
	}

}