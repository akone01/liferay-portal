/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.servlet.jsp.compiler.internal;

import static org.phidias.compile.Constants.JAVA_PACKAGE;
import static org.phidias.compile.Constants.STAR;

import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;

import java.io.File;
import java.io.IOException;

import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;

import org.apache.felix.utils.log.Logger;

import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

import org.phidias.compile.BundleJavaFileObject;
import org.phidias.compile.Constants;
import org.phidias.compile.JarJavaFileObject;
import org.phidias.compile.ResourceResolver;

/**
 * @author Raymond Augé
 * @author Shuyang Zhou
 */
public class BundleJavaFileManager
	extends ForwardingJavaFileManager<JavaFileManager> implements Constants {

	public BundleJavaFileManager(
		Bundle bundle, JavaFileManager javaFileManager, Logger logger,
		boolean verbose, ResourceResolver resourceResolver) {

		super(javaFileManager);

		_logger = logger;
		_verbose = verbose;
		_resourceResolver = resourceResolver;

		if (_verbose) {
			StringBundler sb = new StringBundler(4);

			sb.append("BundleJavaFileManager for bundle: ");
			sb.append(bundle.getSymbolicName());
			sb.append(StringPool.DASH);
			sb.append(bundle.getVersion());

			_logger.log(Logger.LOG_INFO, sb.toString());
		}

		_bundleWiring = bundle.adapt(BundleWiring.class);

		List<BundleWire> providedWires = _bundleWiring.getRequiredWires(null);

		if (_verbose) {
			_logger.log(
				Logger.LOG_INFO,
				"Dependent BundleWirings included in this BundleJavaManager " +
					"context: ");
		}

		_bundleWirings = new LinkedHashSet<>();

		for (BundleWire bundleWire : providedWires) {
			BundleWiring providerWiring = bundleWire.getProviderWiring();

			if (!_bundleWirings.add(providerWiring)) {
				continue;
			}

			Bundle curBundle = providerWiring.getBundle();

			if (curBundle.getBundleId() == 0) {
				List<BundleCapability> bundleCapabilities =
					providerWiring.getCapabilities(
						BundleRevision.PACKAGE_NAMESPACE);

				for (BundleCapability bundleCapability : bundleCapabilities) {
					Map<String, Object> attributes =
						bundleCapability.getAttributes();

					Object packageNamespace = attributes.get(
						BundleRevision.PACKAGE_NAMESPACE);

					if (packageNamespace != null) {
						_systemCapabilities.add(packageNamespace);
					}
				}
			}

			if (_verbose) {
				_logger.log(
					Logger.LOG_INFO,
					"\t" + curBundle.getSymbolicName() + "-" +
						curBundle.getVersion());
			}
		}
	}

	public void addBundleWiring(BundleWiring bundleWiring) {
		_bundleWirings.add(bundleWiring);
	}

	@Override
	public ClassLoader getClassLoader(Location location) {
		if (location != StandardLocation.CLASS_PATH) {
			return fileManager.getClassLoader(location);
		}

		return _bundleWiring.getClassLoader();
	}

	@Override
	public String inferBinaryName(Location location, JavaFileObject file) {
		if ((location == StandardLocation.CLASS_PATH) &&
			(file instanceof BundleJavaFileObject)) {

			BundleJavaFileObject bundleJavaFileObject =
				(BundleJavaFileObject)file;

			if (_verbose) {
				_logger.log(
					Logger.LOG_INFO,
					"Infering binary name from " + bundleJavaFileObject);
			}

			return bundleJavaFileObject.inferBinaryName();
		}

		return fileManager.inferBinaryName(location, file);
	}

	@Override
	public Iterable<JavaFileObject> list(
			Location location, String packageName, Set<Kind> kinds,
			boolean recurse)
		throws IOException {

		if ((location == StandardLocation.CLASS_PATH) && _verbose) {
			_logger.log(
				Logger.LOG_INFO,
				"List available sources for {location=" + location +
					", packageName=" + packageName + ", kinds=" + kinds +
						", recurse=" + recurse + "}");
		}

		String packagePath = packageName.replace('.', '/');

		if (!packageName.startsWith(JAVA_PACKAGE) &&
			(location == StandardLocation.CLASS_PATH)) {

			List<JavaFileObject> javaFileObjects = listFromDependencies(
				kinds, recurse, packagePath);

			if (javaFileObjects.isEmpty() &&
				_systemCapabilities.contains(packageName)) {

				return fileManager.list(location, packagePath, kinds, recurse);
			}

			return javaFileObjects;
		}

		return fileManager.list(location, packagePath, kinds, recurse);
	}

	private String getClassNameFromPath(String resourceName) {
		if (resourceName.endsWith(".class")) {
			resourceName = resourceName.substring(0, resourceName.length() - 6);
		}

		return resourceName.replace('/', '.');
	}

	private JavaFileObject getJavaFileObject(
		URL resourceURL, String resourceName) {

		String protocol = resourceURL.getProtocol();

		String className = getClassNameFromPath(resourceName);

		if (protocol.equals("bundle") || protocol.equals("bundleresource")) {
			try {
				return new BundleJavaFileObject(
					resourceURL.toURI(), className, resourceURL);
			}
			catch (URISyntaxException urise) {
				if (_verbose) {
					_logger.log(Logger.LOG_ERROR, urise.getMessage(), urise);
				}
			}
		}
		else if (protocol.equals("jar")) {
			try {
				JarURLConnection jarUrlConnection =
					(JarURLConnection)resourceURL.openConnection();

				URL url = jarUrlConnection.getJarFileURL();

				return new JarJavaFileObject(
					url.toURI(), className, resourceURL, resourceName);
			}
			catch (Exception e) {
				if (_verbose) {
					_logger.log(Logger.LOG_ERROR, e.getMessage(), e);
				}
			}
		}
		else if (protocol.equals("vfs")) {
			try {
				String filePath = resourceURL.getFile();

				int indexOf = filePath.indexOf(".jar") + 4;

				filePath =
					filePath.substring(0, indexOf) + "!" +
						filePath.substring(indexOf, filePath.length());

				File file = new File(filePath);

				URI uri = file.toURI();

				return new JarJavaFileObject(
					uri, className, new URL("jar:" + uri.toString()),
					resourceName);
			}
			catch (MalformedURLException murie) {
				if (_verbose) {
					_logger.log(Logger.LOG_ERROR, murie.getMessage(), murie);
				}
			}
		}

		return null;
	}

	private void list(
		String packagePath, Kind kind, int options, BundleWiring bundleWiring,
		List<JavaFileObject> javaFileObjects) {

		Collection<String> resources = _resourceResolver.resolveResources(
			bundleWiring, packagePath, STAR.concat(kind.extension), options);

		if ((resources == null) || resources.isEmpty()) {
			return;
		}

		for (String resourceName : resources) {
			URL resourceURL = _resourceResolver.getResource(
				bundleWiring, resourceName);

			JavaFileObject javaFileObject = getJavaFileObject(
				resourceURL, resourceName);

			if (javaFileObject == null) {
				if (_verbose) {
					_logger.log(
						Logger.LOG_INFO,
						"\tCould not create JavaFileObject for {" +
							resourceURL + "}");
				}

				continue;
			}

			if (_verbose) {
				_logger.log(Logger.LOG_INFO, "\t" + javaFileObject);
			}

			javaFileObjects.add(javaFileObject);
		}
	}

	private List<JavaFileObject> listFromDependencies(
		Set<Kind> kinds, boolean recurse, String packagePath) {

		List<JavaFileObject> javaFileObjects = new ArrayList<>();

		int options = recurse ? BundleWiring.LISTRESOURCES_RECURSE : 0;

		for (Kind kind : kinds) {
			if (kind.equals(Kind.CLASS)) {
				for (BundleWiring bundleWiring : _bundleWirings) {
					list(
						packagePath, kind, options, bundleWiring,
						javaFileObjects);
				}
			}

			if (javaFileObjects.isEmpty()) {
				list(
					packagePath, kind, options, _bundleWiring, javaFileObjects);
			}
		}

		return javaFileObjects;
	}

	private final BundleWiring _bundleWiring;
	private final Set<BundleWiring> _bundleWirings;
	private final Logger _logger;
	private final ResourceResolver _resourceResolver;
	private final Set<Object> _systemCapabilities = new HashSet<>();
	private final boolean _verbose;

}