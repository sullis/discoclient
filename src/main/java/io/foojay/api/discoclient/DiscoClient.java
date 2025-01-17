/*
 * Copyright (c) 2021, Azul
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer
 *   in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Azul nor the names of its contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL AZUL BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY,
 * OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.foojay.api.discoclient;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.foojay.api.discoclient.event.CacheEvt;
import io.foojay.api.discoclient.event.DCEvt;
import io.foojay.api.discoclient.event.DownloadEvt;
import io.foojay.api.discoclient.event.Evt;
import io.foojay.api.discoclient.event.EvtObserver;
import io.foojay.api.discoclient.event.EvtType;
import io.foojay.api.discoclient.pkg.Architecture;
import io.foojay.api.discoclient.pkg.ArchiveType;
import io.foojay.api.discoclient.pkg.Bitness;
import io.foojay.api.discoclient.pkg.Distribution;
import io.foojay.api.discoclient.pkg.Latest;
import io.foojay.api.discoclient.pkg.LibCType;
import io.foojay.api.discoclient.pkg.MajorVersion;
import io.foojay.api.discoclient.pkg.OperatingSystem;
import io.foojay.api.discoclient.pkg.PackageType;
import io.foojay.api.discoclient.pkg.Pkg;
import io.foojay.api.discoclient.pkg.ReleaseStatus;
import io.foojay.api.discoclient.pkg.Scope;
import io.foojay.api.discoclient.pkg.SemVer;
import io.foojay.api.discoclient.pkg.TermOfSupport;
import io.foojay.api.discoclient.pkg.VersionNumber;
import io.foojay.api.discoclient.util.Comparison;
import io.foojay.api.discoclient.util.Constants;
import io.foojay.api.discoclient.util.Helper;
import io.foojay.api.discoclient.util.OutputFormat;
import io.foojay.api.discoclient.util.PkgInfo;
import io.foojay.api.discoclient.util.ReadableConsumerByteChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;


public class DiscoClient {
    private static final Logger                         LOGGER            = LoggerFactory.getLogger(DiscoClient.class);
    public         final AtomicBoolean                  cacheReady        = new AtomicBoolean(false);
    private        final Queue<Pkg>                     pkgCache          = new ConcurrentLinkedQueue<>(); // Collections.synchronizedList(new LinkedList<>());
    private        final Queue<MajorVersion>            majorVersionCache = new ConcurrentLinkedQueue<>(); // Collections.synchronizedList(new LinkedList<>());
    private        final Map<String, List<EvtObserver>> observers         = new ConcurrentHashMap<>();
    private        final ScheduledExecutorService       service           = Executors.newScheduledThreadPool(2);
    private        final Runnable                       updateCache       = () -> {
        cacheReady.set(false);
        fireEvt(new CacheEvt(DiscoClient.this, CacheEvt.CACHE_UPDATING));
        pkgCache.clear();
        getAllPackagesAsync().thenAccept(r -> {
            List<Pkg>    tmpList = new LinkedList<>(r);
            HashSet<Pkg> unique  = new HashSet<>(tmpList);
            pkgCache.addAll(unique);
            cacheReady.set(true);
            fireEvt(new CacheEvt(DiscoClient.this, CacheEvt.CACHE_READY));
        });
    };


    public DiscoClient() {
        getAllMajorVersionsAsync(true).thenAccept(r -> majorVersionCache.addAll(r));
        service.scheduleAtFixedRate(updateCache, 1, 3600, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> service.shutdownNow()));
    }


    public Queue<Pkg> getAllPackages() {
        if (cacheReady.get()) { return pkgCache; }

        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.PACKAGES_PATH)
                                                        .append("?release_status=ea")
                                                        .append("&release_status=ga");

        String query = queryBuilder.toString();
        if (query.isEmpty()) { return new ConcurrentLinkedQueue<>(); }

        Queue<Pkg> pkgs     = new ConcurrentLinkedQueue<>();
        List<Pkg> pkgsFound = new ArrayList<>();

        String      bodyText = Helper.get(query);
        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject bundleJsonObj = jsonArray.get(i).getAsJsonObject();
                pkgsFound.add(new Pkg(bundleJsonObj.toString()));
            }
        }

        pkgs.addAll(pkgsFound);
        HashSet<Pkg> unique = new HashSet<>(pkgs);
        pkgs = new LinkedList<>(unique);

        return pkgs;
    }
    public CompletableFuture<Queue<Pkg>> getAllPackagesAsync() {
        if (cacheReady.get()) {
            CompletableFuture<Queue<Pkg>> future = new CompletableFuture<>();
            future.complete(pkgCache);
            return future;
        }
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.PACKAGES_PATH)
                                                        .append("?release_status=ea")
                                                        .append("&release_status=ga");
        String query = queryBuilder.toString();

        CompletableFuture<Queue<Pkg>> future = Helper.getAsync(query).thenApply(response -> {
            if (cacheReady.get()) { return pkgCache; }
            Queue<Pkg>  pkgsFound = new ConcurrentLinkedQueue<>();
            Gson        gson      = new Gson();
            JsonElement element   = gson.fromJson(response, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject bundleJsonObj = jsonArray.get(i).getAsJsonObject();
                    pkgsFound.add(new Pkg(bundleJsonObj.toString()));
                }
            }
            return pkgsFound;
        });
        return future;
    }


    public List<Pkg> getPkgs(final Distribution distribution, final VersionNumber versionNumber, final Latest latest, final OperatingSystem operatingSystem,
                             final LibCType libcType, final Architecture architecture, final Bitness bitness, final ArchiveType archiveType, final PackageType packageType,
                             final Boolean javafxBundled, final Boolean directlyDownloadable, final ReleaseStatus releaseStatus, final TermOfSupport termOfSupport, final Scope scope) {

        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.PACKAGES_PATH);
        final int initialLength = queryBuilder.length();

        Distribution distributionCache = Distribution.NONE;
        if (null != distribution && Distribution.NONE != distribution && Distribution.NOT_FOUND != distribution) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_DISTRIBUTION).append("=").append(distribution.getApiString());
            distributionCache = distribution;
        }

        if (null != versionNumber) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_VERSION).append("=").append(versionNumber.toString());
        }

        Latest latestCache = Latest.NONE;
        if (null != latest && Latest.NONE != latest && Latest.NOT_FOUND != latest) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_LATEST).append("=").append(latest.getApiString());
            latestCache = latest;
        }

        OperatingSystem operatingSystemCache = OperatingSystem.NONE;
        if (null != operatingSystem && OperatingSystem.NONE != operatingSystem && OperatingSystem.NOT_FOUND != operatingSystem) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_OPERATING_SYSTEM).append("=").append(operatingSystem.getApiString());
            operatingSystemCache = operatingSystem;
        }

        LibCType libcTypeCache = LibCType.NONE;
        if (null != libcType && LibCType.NONE != libcType && LibCType.NOT_FOUND != libcType) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_LIBC_TYPE).append("=").append(libcType.getApiString());
            libcTypeCache = libcType;
        }

        Architecture architectureCache = Architecture.NONE;
        if (null != architecture && Architecture.NONE != architecture && Architecture.NOT_FOUND != architecture) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_ARCHITECTURE).append("=").append(architecture.getApiString());
            architectureCache = architecture;
        }

        Bitness bitnessCache = Bitness.NONE;
        if (null != bitness && Bitness.NONE != bitness && Bitness.NOT_FOUND != bitness) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_BITNESS).append("=").append(bitness.getApiString());
            bitnessCache = bitness;
        }

        ArchiveType archiveTypeCache = ArchiveType.NONE;
        if (null != archiveType && ArchiveType.NONE != archiveType && ArchiveType.NOT_FOUND != archiveType) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_ARCHIVE_TYPE).append("=").append(archiveType.getApiString());
            archiveTypeCache = archiveType;
        }

        PackageType packageTypeCache = PackageType.NONE;
        if (null != packageType && PackageType.NONE != packageType && PackageType.NOT_FOUND != packageType) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_PACKAGE_TYPE).append("=").append(packageType.getApiString());
            packageTypeCache = packageType;
        }

        Scope scopeCache = Scope.PUBLIC;
        if (null != scope && Scope.NONE != scope && Scope.NOT_FOUND != scope) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_DISCOVERY_SCOPE_ID).append("=").append(scope.getApiString());
            scopeCache = scope;
        }

        if (null != javafxBundled) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_JAVAFX_BUNDLED).append("=").append(javafxBundled);
        }

        if (null != directlyDownloadable) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_DIRECTLY_DOWNLOADABLE).append("=").append(directlyDownloadable);
        }

        ReleaseStatus releaseStatusCache = ReleaseStatus.NONE;
        if (null != releaseStatus && ReleaseStatus.NONE != releaseStatus && ReleaseStatus.NOT_FOUND != releaseStatus) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_RELEASE_STATUS).append("=").append(releaseStatus.getApiString());
            releaseStatusCache = releaseStatus;
        }

        TermOfSupport termOfSupportCache = TermOfSupport.NONE;
        if (null != termOfSupport && TermOfSupport.NONE != termOfSupport && TermOfSupport.NOT_FOUND != termOfSupport) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_SUPPORT_TERM).append("=").append(termOfSupport.getApiString());
            termOfSupportCache = termOfSupport;
        }

        String query = queryBuilder.toString();
        if (query.isEmpty()) { return List.of(); }

        if (cacheReady.get()) {
            return getPkgsFromCache(versionNumber,
                                    Comparison.EQUAL,
                                    Distribution.NONE    == distributionCache    ? new ArrayList<>() : List.of(distributionCache),
                                    Architecture.NONE    == architectureCache    ? new ArrayList<>() : List.of(architectureCache),
                                    ArchiveType.NONE     == archiveTypeCache     ? new ArrayList<>() : List.of(archiveTypeCache),
                                    packageTypeCache,
                                    OperatingSystem.NONE == operatingSystemCache ? new ArrayList<>() : List.of(operatingSystemCache),
                                    LibCType.NONE        == libcTypeCache        ? new ArrayList<>() : List.of(libcTypeCache),
                                    ReleaseStatus.NONE   == releaseStatusCache   ? new ArrayList<>() : List.of(releaseStatusCache),
                                    TermOfSupport.NONE   == termOfSupportCache   ? new ArrayList<>() : List.of(termOfSupportCache),
                                    bitnessCache,
                                    javafxBundled,
                                    directlyDownloadable,
                                    latestCache,
                                    Scope.NONE           == scopeCache           ? new ArrayList<>() : List.of(scopeCache));
        }

        List<Pkg>   pkgs     = new LinkedList<>();
        String      bodyText = Helper.get(query);

        List<Pkg>   pkgsFound = new ArrayList<>();
        Gson        gson      = new Gson();
        JsonElement element   = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject pkgJsonObj = jsonArray.get(i).getAsJsonObject();
                pkgsFound.add(new Pkg(pkgJsonObj.toString()));
            }
        }

        pkgs.addAll(pkgsFound);
        HashSet<Pkg> unique = new HashSet<>(pkgs);
        pkgs = new LinkedList<>(unique);

        return pkgs;
    }
    public CompletableFuture<List<Pkg>> getPkgsAsync(final Distribution distribution, final VersionNumber versionNumber, final Latest latest, final OperatingSystem operatingSystem,
                                                     final LibCType libCType, final Architecture architecture, final Bitness bitness, final ArchiveType archiveType, final PackageType packageType,
                                                     final Boolean javafxBundled, final Boolean directlyDownloadable, final ReleaseStatus releaseStatus, final TermOfSupport termOfSupport, final Scope scope) {

        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.PACKAGES_PATH);
        final int initialLength = queryBuilder.length();

        Distribution distributionCache = Distribution.NONE;
        if (null != distribution && Distribution.NONE != distribution && Distribution.NOT_FOUND != distribution) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_DISTRIBUTION).append("=").append(distribution.getApiString());
            distributionCache = distribution;
        }

        if (null != versionNumber) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_VERSION).append("=").append(versionNumber.toString(OutputFormat.REDUCED, true, true));
        }

        Latest latestCache = Latest.NONE;
        if (null != latest && Latest.NONE != latest && Latest.NOT_FOUND != latest) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_LATEST).append("=").append(latest.getApiString());
            latestCache = latest;
        }

        OperatingSystem operatingSystemCache = OperatingSystem.NONE;
        if (null != operatingSystem && OperatingSystem.NONE != operatingSystem && OperatingSystem.NOT_FOUND != operatingSystem) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_OPERATING_SYSTEM).append("=").append(operatingSystem.getApiString());
            operatingSystemCache = operatingSystem;
        }

        LibCType libcTypeCache = LibCType.NONE;
        if (null != libCType && LibCType.NONE != libCType && LibCType.NOT_FOUND != libCType) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_LIBC_TYPE).append("=").append(libCType.getApiString());
            libcTypeCache = libCType;
        }

        Architecture architectureCache = Architecture.NONE;
        if (null != architecture && Architecture.NONE != architecture && Architecture.NOT_FOUND != architecture) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_ARCHITECTURE).append("=").append(architecture.getApiString());
            architectureCache = architecture;
        }

        Bitness bitnessCache = Bitness.NONE;
        if (null != bitness && Bitness.NONE != bitness && Bitness.NOT_FOUND != bitness) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_BITNESS).append("=").append(bitness.getApiString());
            bitnessCache = bitness;
        }

        ArchiveType archiveTypeCache = ArchiveType.NONE;
        if (null != archiveType && ArchiveType.NONE != archiveType && ArchiveType.NOT_FOUND != archiveType) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_ARCHIVE_TYPE).append("=").append(archiveType.getApiString());
            archiveTypeCache = archiveType;
        }

        PackageType packageTypeCache = PackageType.NONE;
        if (null != packageType && PackageType.NONE != packageType && PackageType.NOT_FOUND != packageType) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_PACKAGE_TYPE).append("=").append(packageType.getApiString());
            packageTypeCache = packageType;
        }

        Scope scopeCache = Scope.PUBLIC;
        if (null != scope && Scope.NONE != scope && Scope.NOT_FOUND != scope) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_DISCOVERY_SCOPE_ID).append("=").append(scope.getApiString());
            scopeCache = scope;
        }

        if (null != javafxBundled) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_JAVAFX_BUNDLED).append("=").append(javafxBundled);
        }

        if (null != directlyDownloadable) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_DIRECTLY_DOWNLOADABLE).append("=").append(directlyDownloadable);
        }

        ReleaseStatus releaseStatusCache = ReleaseStatus.NONE;
        if (null != releaseStatus && ReleaseStatus.NONE != releaseStatus && ReleaseStatus.NOT_FOUND != releaseStatus) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_RELEASE_STATUS).append("=").append(releaseStatus.getApiString());
            releaseStatusCache = releaseStatus;
        }

        TermOfSupport termOfSupportCache = TermOfSupport.NONE;
        if (null != termOfSupport && TermOfSupport.NONE != termOfSupport && TermOfSupport.NOT_FOUND != termOfSupport) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append(Constants.API_SUPPORT_TERM).append("=").append(termOfSupport.getApiString());
            termOfSupportCache = termOfSupport;
        }

        String query = queryBuilder.toString();
        if (query.isEmpty()) { return new CompletableFuture<>(); }

        if (cacheReady.get()) {
            CompletableFuture<List<Pkg>> future = new CompletableFuture<>();
            future.complete(getPkgsFromCache(versionNumber,
                                             Comparison.EQUAL,
                                             Distribution.NONE    == distributionCache    ? new ArrayList<>() : List.of(distributionCache),
                                             Architecture.NONE    == architectureCache    ? new ArrayList<>() : List.of(architectureCache),
                                             ArchiveType.NONE     == archiveTypeCache     ? new ArrayList<>() : List.of(archiveTypeCache),
                                             packageTypeCache,
                                             OperatingSystem.NONE == operatingSystemCache ? new ArrayList<>() : List.of(operatingSystemCache),
                                             LibCType.NONE        == libcTypeCache        ? new ArrayList<>() : List.of(libcTypeCache),
                                             ReleaseStatus.NONE   == releaseStatusCache   ? new ArrayList<>() : List.of(releaseStatusCache),
                                             TermOfSupport.NONE   == termOfSupportCache   ? new ArrayList<>() : List.of(termOfSupportCache),
                                             bitnessCache,
                                             javafxBundled,
                                             directlyDownloadable,
                                             latestCache,
                                             Scope.NONE           == scopeCache           ? new ArrayList<>() : List.of(scopeCache)));
            return future;
        }
        return Helper.getAsync(query).thenApply(bodyText -> {
            List<Pkg>   pkgs      = new LinkedList<>();
            List<Pkg>   pkgsFound = new ArrayList<>();
            Gson        gson      = new Gson();
            JsonElement element   = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject pkgJsonObj = jsonArray.get(i).getAsJsonObject();
                    pkgsFound.add(new Pkg(pkgJsonObj.toString()));
                }
            }
            pkgs.addAll(pkgsFound);
            HashSet<Pkg> unique = new HashSet<>(pkgs);
            pkgs = new LinkedList<>(unique);
            return pkgs;
        });
    }


    public String getPkgsAsJson(final Distribution distribution, final VersionNumber versionNumber, final Latest latest, final OperatingSystem operatingSystem,
                                final LibCType libcType, final Architecture architecture, final Bitness bitness, final ArchiveType archiveType, final PackageType packageType,
                                final Boolean javafxBundled, final Boolean directlyDownloadable, final ReleaseStatus releaseStatus, final TermOfSupport termOfSupport, final Scope scope) {
        return getPkgs(distribution, versionNumber, latest, operatingSystem, libcType, architecture, bitness, archiveType, packageType, javafxBundled, directlyDownloadable, releaseStatus, termOfSupport, scope).toString();
    }
    public CompletableFuture<String> getPkgsAsJsonAsync(final Distribution distribution, final VersionNumber versionNumber, final Latest latest, final OperatingSystem operatingSystem,
                                                        final LibCType libcType, final Architecture architecture, final Bitness bitness, final ArchiveType archiveType, final PackageType packageType,
                                                        final Boolean javafxBundled, final Boolean directlyDownloadable, final ReleaseStatus releaseStatus, final TermOfSupport termOfSupport, final Scope scope) {
        return getPkgsAsync(distribution, versionNumber, latest, operatingSystem, libcType, architecture, bitness, archiveType, packageType, javafxBundled, directlyDownloadable, releaseStatus, termOfSupport, scope).thenApply(pkgs -> pkgs.toString());
    }


    public final MajorVersion getMajorVersion(final String parameter) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH);
        if (null != parameter || !parameter.isEmpty()) {
            queryBuilder.append("/").append(parameter);
        }

        String query = queryBuilder.toString();
        if (query.isEmpty()) {
            LOGGER.debug("No major version found for given parameter {}.", parameter);
            return null;
        }
        String      bodyText = Helper.get(query);
        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonObject) {
            JsonObject    json         = element.getAsJsonObject();
            MajorVersion  majorVersion = new MajorVersion(json.toString());
            return majorVersion;
        } else {
            return null;
        }
    }
    public final CompletableFuture<MajorVersion> getMajorVersionAsync(final String parameter) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH);
        if (null != parameter || !parameter.isEmpty()) {
            queryBuilder.append("/").append(parameter);
        }

        String query = queryBuilder.toString();
        if (query.isEmpty()) {
            LOGGER.debug("No major version found for given parameter {}.", parameter);
            return null;
        }
        return Helper.getAsync(query).thenApply(bodyText -> {
            Gson        gson     = new Gson();
            JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonObject) {
                JsonObject    json         = element.getAsJsonObject();
                MajorVersion  majorVersion = new MajorVersion(json.toString());
                return majorVersion;
            } else {
                return null;
            }
        });
    }


    public final Queue<MajorVersion> getAllMajorVersions() { return getAllMajorVersions(false); }
    public final Queue<MajorVersion> getAllMajorVersions(final boolean include_ea) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH)
                                                        .append("?ea=")
                                                        .append(include_ea);

        String              query              = queryBuilder.toString();
        String              bodyText           = Helper.get(query);
        Queue<MajorVersion> majorVersionsFound = new ConcurrentLinkedQueue<>();

        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject majorVersionJsonObj = jsonArray.get(i).getAsJsonObject();
                majorVersionsFound.add(new MajorVersion(majorVersionJsonObj.toString()));
            }
        }
        return majorVersionsFound;
    }
    public final List<MajorVersion> getAllMajorVersions(final Optional<Boolean> maintained, final Optional<Boolean> includingEA, final Optional<Boolean> includingGA) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH);
        int initialLength = queryBuilder.length();
        if (null != maintained && maintained.isPresent()) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append("maintained=").append(maintained.get());
        }
        if (null != includingEA && includingEA.isPresent()) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append("ea=").append(includingEA.get());
        }
        if (null != includingGA && includingGA.isPresent()) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append("ga=").append(includingGA.get());
        }

        String             query              = queryBuilder.toString();
        String             bodyText           = Helper.get(query);
        List<MajorVersion> majorVersionsFound = new ArrayList<>();

        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject majorVersionJsonObj = jsonArray.get(i).getAsJsonObject();
                majorVersionsFound.add(new MajorVersion(majorVersionJsonObj.toString()));
            }
        }
        return majorVersionsFound;
    }


    public final CompletableFuture<List<MajorVersion>> getAllMajorVersionsAsync() { return getAllMajorVersionsAsync(false); }
    public final CompletableFuture<List<MajorVersion>> getAllMajorVersionsAsync(final boolean include_ea) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH)
                                                        .append("?ea=")
                                                        .append(include_ea);
        String query = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(bodyText -> {
            List<MajorVersion> majorVersionsFound = new CopyOnWriteArrayList<>();
            Gson        gson     = new Gson();
            JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject majorVersionJsonObj = jsonArray.get(i).getAsJsonObject();
                    majorVersionsFound.add(new MajorVersion(majorVersionJsonObj.toString()));
                }
            }
            return majorVersionsFound;
        });
    }
    public final CompletableFuture<List<MajorVersion>> getAllMajorVersionsAsync(final Optional<Boolean> maintained, final Optional<Boolean> includingEA, final Optional<Boolean> includingGA) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH);
        int initialLength = queryBuilder.length();
        if (null != maintained && maintained.isPresent()) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append("maintained=").append(maintained.get());
        }
        if (null != includingEA && includingEA.isPresent()) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append("ea=").append(includingEA.get());
        }
        if (null != includingGA && includingGA.isPresent()) {
            queryBuilder.append(queryBuilder.length() == initialLength ? "?" : "&");
            queryBuilder.append("ga=").append(includingGA.get());
        }

        String query = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(bodyText -> {
            List<MajorVersion> majorVersionsFound = new ArrayList<>();
            Gson        gson     = new Gson();
            JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject majorVersionJsonObj = jsonArray.get(i).getAsJsonObject();
                    majorVersionsFound.add(new MajorVersion(majorVersionJsonObj.toString()));
                }
            }
            return majorVersionsFound;
        });
    }


    public final MajorVersion getMajorVersion(final int featureVersion, final boolean include_ea) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH)
                                                        .append("?include_ea=")
                                                        .append(include_ea);

        String query    = queryBuilder.toString();
        String bodyText = Helper.get(query);

        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject   json         = jsonArray.get(i).getAsJsonObject();
                MajorVersion majorVersion = new MajorVersion(json.toString());
                if (majorVersion.getAsInt() == featureVersion) {
                    return majorVersion;
                }
            }
            return null;
        } else {
            return null;
        }
    }
    public final CompletableFuture<MajorVersion> getMajorVersionAsync(final int featureVersion, final boolean include_ea) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH)
                                                        .append("?include_ea=")
                                                        .append(include_ea);

        String query = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(bodyText -> {
            Gson        gson     = new Gson();
            JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject   json         = jsonArray.get(i).getAsJsonObject();
                    MajorVersion majorVersion = new MajorVersion(json.toString());
                    if (majorVersion.getAsInt() == featureVersion) {
                        return majorVersion;
                    }
                }
                return null;
            } else {
                return null;
            }
        });
    }


    public final String getMajorVersionAsJson(final String parameter) {
        MajorVersion majorVersion = getMajorVersion(parameter);
        if (null == majorVersion) {
            return new StringBuilder().append("{").append("\n")
                                      .append("  \"value\"").append(":").append("\"").append(majorVersion).append("\"").append(",").append("\n")
                                      .append("  \"detail\"").append(":").append("\"Requested release has wrong format or is null.\"").append(",").append("\n")
                                      .append("  \"supported\"").append(":").append("\"1 - next early access").append(",current, last, latest, next, prev, last_lts, latest_lts, last_mts, latest_mts, last_sts, latest_mts, next_lts, next_mts, next_sts\"").append("\n")
                                      .append("}")
                                      .toString();
        } else {
            return majorVersion.toString();
        }
    }
    public final CompletableFuture<String> getMajorVersionAsJsonAsync(final String parameter) {
        return getMajorVersionAsync(parameter).thenApply(majorVersion -> {
            if (null == majorVersion) {
                return new StringBuilder().append("{").append("\n")
                                          .append("  \"value\"").append(":").append("\"").append(majorVersion).append("\"").append(",").append("\n")
                                          .append("  \"detail\"").append(":").append("\"Requested release has wrong format or is null.\"").append(",").append("\n")
                                          .append("  \"supported\"").append(":").append("\"1 - next early access").append(",current, last, latest, next, prev, last_lts, latest_lts, last_mts, latest_mts, last_sts, latest_mts, next_lts, next_mts, next_sts\"").append("\n")
                                          .append("}")
                                          .toString();
            } else {
                return majorVersion.toString();
            }
        });
    }


    public final List<MajorVersion> getMaintainedMajorVersions() { return getMaintainedMajorVersions(false); }
    public final List<MajorVersion> getMaintainedMajorVersions(final boolean include_ea) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH)
                                                        .append("?maintained=true&ga=true")
                                                        .append(include_ea ? "&ea=true" : "")
                                                        .append(include_ea);

        String             query              = queryBuilder.toString();
        String             bodyText           = Helper.get(query);
        List<MajorVersion> majorVersionsFound = new ArrayList<>();

        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject majorVersionJsonObj = jsonArray.get(i).getAsJsonObject();
                majorVersionsFound.add(new MajorVersion(majorVersionJsonObj.toString()));
            }
        }
        return majorVersionsFound;
    }


    public final CompletableFuture<List<MajorVersion>> getMaintainedMajorVersionsAsync() { return getMaintainedMajorVersionsAsync(false); }
    public final CompletableFuture<List<MajorVersion>> getMaintainedMajorVersionsAsync(final boolean include_ea) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH)
                                                        .append("?maintained=true&ga=true")
                                                        .append(include_ea ? "&ea=true" : "")
                                                        .append(include_ea);

        String query = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(bodyText -> {
            List<MajorVersion> majorVersionsFound = new ArrayList<>();

            Gson        gson     = new Gson();
            JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject majorVersionJsonObj = jsonArray.get(i).getAsJsonObject();
                    majorVersionsFound.add(new MajorVersion(majorVersionJsonObj.toString()));
                }
            }
            return majorVersionsFound;
        });
    }


    public final List<MajorVersion> getUsefulMajorVersions() {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH)
                                                        .append("/useful");

        String             query              = queryBuilder.toString();
        String             bodyText           = Helper.get(query);
        List<MajorVersion> majorVersionsFound = new ArrayList<>();

        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject majorVersionJsonObj = jsonArray.get(i).getAsJsonObject();
                majorVersionsFound.add(new MajorVersion(majorVersionJsonObj.toString()));
            }
        }
        return majorVersionsFound;
    }


    public final CompletableFuture<List<MajorVersion>> getUsefulMajorVersionsAsync() {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.MAJOR_VERSIONS_PATH)
                                                        .append("/useful");

        String query = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(bodyText -> {
            List<MajorVersion> majorVersionsFound = new ArrayList<>();

            Gson        gson     = new Gson();
            JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject majorVersionJsonObj = jsonArray.get(i).getAsJsonObject();
                    majorVersionsFound.add(new MajorVersion(majorVersionJsonObj.toString()));
                }
            }
            return majorVersionsFound;
        });
    }


    public final MajorVersion getLatestLts(final boolean including_ea) {
        Queue<MajorVersion> majorVersions = getAllMajorVersions(including_ea);
        return majorVersions.stream()
                            .filter(majorVersion -> TermOfSupport.LTS == majorVersion.getTermOfSupport())
                            .filter(majorVersion -> including_ea ? majorVersion.getVersions().size() > 0 : majorVersion.getVersions().size() > 1)
                            .findFirst().get();
    }
    public final CompletableFuture<MajorVersion> getLatestLtsAsync(final boolean including_ea) {
        return getAllMajorVersionsAsync(including_ea).thenApply(majorVersions -> majorVersions.stream()
                                                                                              .filter(majorVersion -> TermOfSupport.LTS == majorVersion.getTermOfSupport())
                                                                                              .filter(majorVersion -> including_ea ? majorVersion.getVersions().size() > 0 : majorVersion.getVersions().size() > 1)
                                                                                              .findFirst().get());

    }


    public final MajorVersion getLatestMts(final boolean including_ea) {
        Queue<MajorVersion> majorVersions = getAllMajorVersions(including_ea);
        return majorVersions.stream()
                            .filter(majorVersion -> TermOfSupport.MTS == majorVersion.getTermOfSupport())
                            .filter(majorVersion -> including_ea ? majorVersion.getVersions().size() > 0 : majorVersion.getVersions().size() > 1)
                            .findFirst().get();
    }
    public final CompletableFuture<MajorVersion> getLatestMtsAsync(final boolean including_ea) {
        return getAllMajorVersionsAsync(including_ea).thenApply(majorVersions -> majorVersions.stream()
                                                                                              .filter(majorVersion -> TermOfSupport.MTS == majorVersion.getTermOfSupport())
                                                                                              .filter(majorVersion -> including_ea ? majorVersion.getVersions().size() > 0 : majorVersion.getVersions().size() > 1)
                                                                                              .findFirst().get());
    }


    public final MajorVersion getLatestSts(final boolean including_ea) {
        Queue<MajorVersion> majorVersions = getAllMajorVersions(including_ea);
        return majorVersions.stream()
                            .filter(majorVersion -> TermOfSupport.LTS != majorVersion.getTermOfSupport())
                            .filter(majorVersion -> including_ea ? majorVersion.getVersions().size() > 0 : majorVersion.getVersions().size() > 1)
                            .findFirst().get();
    }
    public final CompletableFuture<MajorVersion> getLatestStsAsync(final boolean including_ea) {
        return getAllMajorVersionsAsync(including_ea).thenApply(majorVersions -> majorVersions.stream()
                                                                                              .filter(majorVersion -> TermOfSupport.LTS != majorVersion.getTermOfSupport())
                                                                                              .filter(majorVersion -> including_ea ? majorVersion.getVersions().size() > 0 : majorVersion.getVersions().size() > 1)
                                                                                              .findFirst().get());
    }


    public final List<Distribution> getDistributionsThatSupportVersion(final String version) {
        SemVer semver = SemVer.fromText(version).getSemVer1();
        if (null == semver) {
            LOGGER.debug("Error parsing version string {} to semver", version);
            return new ArrayList<>();
        }
        return getDistributionsThatSupportVersion(semver);
    }
    public final List<Distribution> getDistributionsThatSupportVersion(final SemVer semVer) {
        return getDistributionsForSemVer(semVer);
    }
    public final CompletableFuture<List<Distribution>> getDistributionsThatSupportSemVerAsync(final SemVer semVer) {
        return getDistributionsForSemVerAsync(semVer);
    }


    public final CompletableFuture<List<Distribution>> getDistributionsThatSupportVersionAsync(final String version) {
        return getDistributionsThatSupportVersionAsync(VersionNumber.fromText(version));
    }
    public final CompletableFuture<List<Distribution>> getDistributionsThatSupportVersionAsync(final VersionNumber versionNumber) {
        return getDistributionsForVersionAsync(versionNumber);
    }
    public final List<Distribution> getDistributionsThatSupportVersion(final VersionNumber versionNumber) {
        return getDistributionsForVersion(versionNumber);
    }


    public final List<Distribution> getDistributionsThatSupport(final SemVer semVer, final OperatingSystem operatingSystem, final Architecture architecture,
                                                                final LibCType libcType, final ArchiveType archiveType, final PackageType packageType,
                                                                final Boolean javafxBundled, final Boolean directlyDownloadable) {
        return getPkgs(Distribution.NONE, semVer.getVersionNumber(), Latest.NONE, operatingSystem, libcType, architecture,
                       Bitness.NONE, archiveType, packageType, javafxBundled, directlyDownloadable, semVer.getReleaseStatus(),
                       TermOfSupport.NONE, Scope.PUBLIC).stream()
                                                        .map(pkg -> pkg.getDistribution())
                                                        .distinct()
                                                        .collect(Collectors.toList());
    }
    public final CompletableFuture<List<Distribution>> getDistributionsThatSupportAsync(final SemVer semVer, final OperatingSystem operatingSystem, final Architecture architecture,
                                                                                        final LibCType libcType, final ArchiveType archiveType, final PackageType packageType,
                                                                                        final Boolean javafxBundled, final Boolean directlyDownloadable) {
        return getPkgsAsync(Distribution.NONE, semVer.getVersionNumber(), Latest.NONE, operatingSystem, libcType, architecture,
                            Bitness.NONE, archiveType, packageType, javafxBundled, directlyDownloadable, semVer.getReleaseStatus(),
                            TermOfSupport.NONE, Scope.PUBLIC).thenApply(pkgs -> pkgs.stream()
                                                                                    .map(pkg -> pkg.getDistribution())
                                                                                    .distinct()
                                                                                    .collect(Collectors.toList()));
    }


    public final List<Distribution> getDistributions() {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.DISTRIBUTIONS_PATH);

        String             query              = queryBuilder.toString();
        String             bodyText           = Helper.get(query);
        List<Distribution> distributionsFound = new LinkedList<>();

        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject distributionJsonObj = jsonArray.get(i).getAsJsonObject();
                final String api_parameter = distributionJsonObj.get("api_parameter").getAsString();
                distributionsFound.add(Distribution.fromText(api_parameter));
            }
        }
        return distributionsFound;
    }
    public final CompletableFuture<List<Distribution>> getDistributionsAsync() {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.DISTRIBUTIONS_PATH);
        String query = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(bodyText -> {
            List<Distribution> distributionsFound = new LinkedList<>();
            Gson               gson               = new Gson();
            JsonElement        element            = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject distributionJsonObj = jsonArray.get(i).getAsJsonObject();
                    final String api_parameter = distributionJsonObj.get("api_parameter").getAsString();
                    distributionsFound.add(Distribution.fromText(api_parameter));
                }
            }
            return distributionsFound;
        });
    }


    public final List<Distribution> getDistributionsForSemVer(final SemVer semVer) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.DISTRIBUTIONS_PATH)
                                                        .append("/versions/")
                                                        .append(semVer.toString());

        String             query              = queryBuilder.toString();
        String             bodyText           = Helper.get(query);
        List<Distribution> distributionsFound = new LinkedList<>();

        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject distributionJsonObj = jsonArray.get(i).getAsJsonObject();
                final String api_parameter = distributionJsonObj.get("api_parameter").getAsString();
                distributionsFound.add(Distribution.fromText(api_parameter));
            }
        }
        return distributionsFound;
    }
    public final CompletableFuture<List<Distribution>> getDistributionsForSemVerAsync(final SemVer semVer) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.DISTRIBUTIONS_PATH)
                                                        .append("/versions/")
                                                        .append(semVer.toString());

        String query = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(bodyText -> {
            List<Distribution> distributionsFound = new LinkedList<>();
            Gson               gson               = new Gson();
            JsonElement        element            = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject distributionJsonObj = jsonArray.get(i).getAsJsonObject();
                    final String api_parameter = distributionJsonObj.get("api_parameter").getAsString();
                    distributionsFound.add(Distribution.fromText(api_parameter));
                }
            }
            return distributionsFound;
        });
    }


    public final List<Distribution> getDistributionsForVersion(final VersionNumber versionNumber) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.DISTRIBUTIONS_PATH)
                                                        .append("/versions/")
                                                        .append(versionNumber.toString());

        String             query              = queryBuilder.toString();
        String             bodyText           = Helper.get(query);
        List<Distribution> distributionsFound = new LinkedList<>();

        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                JsonObject distributionJsonObj = jsonArray.get(i).getAsJsonObject();
                final String api_parameter = distributionJsonObj.get("api_parameter").getAsString();
                distributionsFound.add(Distribution.fromText(api_parameter));
            }
        }
        return distributionsFound;
    }
    public final CompletableFuture<List<Distribution>> getDistributionsForVersionAsync(final VersionNumber versionNumber) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.DISTRIBUTIONS_PATH)
                                                        .append("/versions/")
                                                        .append(versionNumber.toString());

        String query = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(bodyText -> {
            List<Distribution> distributionsFound = new LinkedList<>();
            Gson               gson               = new Gson();
            JsonElement        element            = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject distributionJsonObj = jsonArray.get(i).getAsJsonObject();
                    final String api_parameter = distributionJsonObj.get("api_parameter").getAsString();
                    distributionsFound.add(Distribution.fromText(api_parameter));
                }
            }
            return distributionsFound;
        });
    }


    public static Map<Distribution, List<VersionNumber>> getVersionsPerDistribution() {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.DISTRIBUTIONS_PATH);

        String                                 query              = queryBuilder.toString();
        String                                 bodyText           = Helper.get(query);
        Map<Distribution, List<VersionNumber>> distributionsFound = new LinkedHashMap<>();
        Gson        gson     = new Gson();
        JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
        if (element instanceof JsonArray) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                final JsonObject          distributionJsonObj = jsonArray.get(i).getAsJsonObject();
                final String              api_parameter       = distributionJsonObj.get("api_parameter").getAsString();
                final Distribution        distribution        = Distribution.fromText(api_parameter);
                final List<VersionNumber> versions            = new LinkedList<>();
                final JsonArray           versionsArray       = distributionJsonObj.get("versions").getAsJsonArray();
                for (int j = 0 ; j < versionsArray.size() ; j++) {
                    VersionNumber versionNumber = VersionNumber.fromText(versionsArray.get(j).getAsString());
                    versions.add(versionNumber);
                }
                distributionsFound.put(distribution, versions);
            }
        }
        return distributionsFound;
    }
    public static CompletableFuture<Map<Distribution, List<VersionNumber>>> getVersionsPerDistributionAsync() {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.DISTRIBUTIONS_PATH);

        String query = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(bodyText -> {
            Map<Distribution, List<VersionNumber>> distributionsFound = new LinkedHashMap<>();
            Gson        gson     = new Gson();
            JsonElement element  = gson.fromJson(bodyText, JsonElement.class);
            if (element instanceof JsonArray) {
                JsonArray jsonArray = element.getAsJsonArray();
                for (int i = 0; i < jsonArray.size(); i++) {
                    final JsonObject          distributionJsonObj = jsonArray.get(i).getAsJsonObject();
                    final String              api_parameter       = distributionJsonObj.get("api_parameter").getAsString();
                    final Distribution        distribution        = Distribution.fromText(api_parameter);
                    final List<VersionNumber> versions            = new LinkedList<>();
                    final JsonArray           versionsArray       = distributionJsonObj.get("versions").getAsJsonArray();
                    for (int j = 0 ; j < versionsArray.size() ; j++) {
                        VersionNumber versionNumber = VersionNumber.fromText(versionsArray.get(j).getAsString());
                        versions.add(versionNumber);
                    }
                    distributionsFound.put(distribution, versions);
                }
            }
            return distributionsFound;
        });
    }


    public List<Distribution> getDistributionsBasedOnOpenJDK() {
        return Distribution.getDistributionsBasedOnOpenJDK();
    }


    public List<Distribution> getDistributionsBasedOnGraalVm() {
        return Distribution.getDistributionsBasedOnGraalVm();
    }


    public final String getPkgDirectDownloadUri(final String id, final SemVer javaVersion) {
        return getPkgInfo(id, javaVersion).getDirectDownloadUri();
    }
    public final CompletableFuture<String> getPkgDirectDownloadUriAsync(final String id, final SemVer javaVersion) {
        return getPkgInfoAsync(id, javaVersion).thenApply(pkgInfo -> pkgInfo.getDirectDownloadUri());
    }


    public PkgInfo getPkgInfo(final String ephemeralId, final SemVer javaVersion) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.EPHEMERAL_IDS_PATH)
                                                        .append("/")
                                                        .append(ephemeralId);

        String query           = queryBuilder.toString();
        String packageInfoBody = Helper.get(query);

        Gson        packageInfoGson    = new Gson();
        JsonElement packageInfoElement = packageInfoGson.fromJson(packageInfoBody, JsonElement.class);
        if (packageInfoElement instanceof JsonObject) {
            final JsonObject packageInfoJson   = packageInfoElement.getAsJsonObject();
            final String     filename          = packageInfoJson.get(PkgInfo.FIELD_FILENAME).getAsString();
            final String     directDownloadUri = packageInfoJson.get(PkgInfo.FIELD_DIRECT_DOWNLOAD_URI).getAsString();
            final String     downloadSiteUri   = packageInfoJson.get(PkgInfo.FIELD_DOWNLOAD_SITE_URI).getAsString();
            return new PkgInfo(filename, javaVersion, directDownloadUri, downloadSiteUri);
        }

        return null;
    }
    public CompletableFuture<PkgInfo> getPkgInfoAsync(final String ephemeralId, final SemVer javaVersion) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.EPHEMERAL_IDS_PATH)
                                                        .append("/")
                                                        .append(ephemeralId);
        String query           = queryBuilder.toString();
        return Helper.getAsync(query).thenApply(packageInfoBody -> {
            Gson        packageInfoGson    = new Gson();
            JsonElement packageInfoElement = packageInfoGson.fromJson(packageInfoBody, JsonElement.class);
            if (packageInfoElement instanceof JsonObject) {
                final JsonObject packageInfoJson   = packageInfoElement.getAsJsonObject();
                final String     filename          = packageInfoJson.get(PkgInfo.FIELD_FILENAME).getAsString();
                final String     directDownloadUri = packageInfoJson.get(PkgInfo.FIELD_DIRECT_DOWNLOAD_URI).getAsString();
                final String     downloadSiteUri   = packageInfoJson.get(PkgInfo.FIELD_DOWNLOAD_SITE_URI).getAsString();
                return new PkgInfo(filename, javaVersion, directDownloadUri, downloadSiteUri);
            }
            return null;
        });
    }


    public final Future<?> downloadPkg(final String pkgId, final String targetFileName) {
        Pkg pkg = getPkg(pkgId);
        if (null == pkg) {
            return null;
        } else {
            final SemVer javaVersion = pkg.getJavaVersion();
            final String ephemeralId = pkg.getEphemeralId();
            return downloadPkg(ephemeralId, javaVersion, targetFileName);
        }
    }
    public final Future<?> downloadPkg(final String ephemeralId, final SemVer javaVersion, final String targetFileName) {
        final String              url      = getPkgInfo(ephemeralId,javaVersion).getDirectDownloadUri();
        final FutureTask<Boolean> task     = createTask(targetFileName, url);
        final ExecutorService     executor = Executors.newSingleThreadExecutor();
        final Future<?>           future   = executor.submit(task);
        executor.shutdown();
        return future;
    }
    public final Future<?> downloadPkg(final PkgInfo pkgInfo, final String targetFileName) {
        final FutureTask<Boolean> task     = createTask(targetFileName, pkgInfo.getDirectDownloadUri());
        final ExecutorService     executor = Executors.newSingleThreadExecutor();
        final Future<?>           future   = executor.submit(task);
        executor.shutdown();
        return future;
    }


    public Pkg getPkg(final String pkgId) {
        if (cacheReady.get()) { return pkgCache.stream().filter(pkg -> pkg.getId().equals(pkgId)).findFirst().orElse(null); }
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.PACKAGES_PATH)
                                                        .append("/")
                                                        .append(pkgId);

        String query    = queryBuilder.toString();
        String bodyText = Helper.get(query);

        Gson        pkgGson    = new Gson();
        JsonElement pkgElement = pkgGson.fromJson(bodyText, JsonElement.class);
        if (pkgElement instanceof JsonObject) {
            return new Pkg(pkgElement.getAsJsonObject().toString());
        } else {
            return null;
        }
    }
    public CompletableFuture<Pkg> getPkgAsync(final String pkgId) {
        StringBuilder queryBuilder = new StringBuilder().append(getDiscoApiUrl())
                                                        .append(Constants.PACKAGES_PATH)
                                                        .append("/")
                                                        .append(pkgId);
        String query = queryBuilder.toString();
        if (cacheReady.get()) {
            CompletableFuture<Pkg> future = new CompletableFuture<>();
            future.complete(pkgCache.stream().filter(pkg -> pkg.getId().equals(pkgId)).findFirst().orElse(null));
            return future;
        }
        return Helper.getAsync(query).thenApply(bodyText -> {
            Gson        pkgGson    = new Gson();
            JsonElement pkgElement = pkgGson.fromJson(bodyText, JsonElement.class);
            if (pkgElement instanceof JsonObject) {
                return new Pkg(pkgElement.getAsJsonObject().toString());
            } else {
                return null;
            }
        });
    }


    public final  OperatingSystem getOperatingSystem() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.indexOf("win") >= 0) {
            return OperatingSystem.WINDOWS;
        } else if (os.indexOf("mac") >= 0) {
            return OperatingSystem.MACOS;
        } else if (os.indexOf("nix") >= 0 || os.indexOf("nux") >= 0) {
            return OperatingSystem.LINUX;
        } else if (os.indexOf("sunos") >= 0) {
            return OperatingSystem.SOLARIS;
        } else {
            return OperatingSystem.NONE;
        }
    }

    public final List<ArchiveType> getArchiveTypes(final OperatingSystem os) {
        switch (os) {
            case WINDOWS     : return List.of(ArchiveType.CAB, ArchiveType.MSI, ArchiveType.TAR, ArchiveType.ZIP);
            case MACOS       : return List.of(ArchiveType.DMG, ArchiveType.PKG, ArchiveType.TAR, ArchiveType.ZIP);
            case LINUX       : return List.of(ArchiveType.DEB, ArchiveType.RPM, ArchiveType.TAR, ArchiveType.ZIP);
            case LINUX_MUSL  : return List.of(ArchiveType.DEB, ArchiveType.RPM, ArchiveType.TAR, ArchiveType.ZIP);
            case ALPINE_LINUX: return List.of(ArchiveType.DEB, ArchiveType.RPM, ArchiveType.TAR, ArchiveType.ZIP);
            case SOLARIS     : return List.of(ArchiveType.DEB, ArchiveType.RPM, ArchiveType.TAR, ArchiveType.ZIP);
            case AIX         : return List.of(ArchiveType.DEB, ArchiveType.RPM, ArchiveType.TAR, ArchiveType.ZIP);
            case QNX         : return List.of(ArchiveType.DEB, ArchiveType.RPM, ArchiveType.TAR, ArchiveType.ZIP);
            default          : return Arrays.stream(ArchiveType.values()).filter(ext -> ArchiveType.NONE != ext).filter(ext -> ArchiveType.NOT_FOUND != ext).collect(Collectors.toList());
        }
    }

    private final FutureTask<Boolean> createTask(final String fileName, final String url) {
        return new FutureTask<>(() -> {
            try {
                final URLConnection         connection = new URL(url).openConnection();
                final int                   fileSize   = connection.getContentLength();
                fireEvt(new DownloadEvt(DiscoClient.this, DownloadEvt.DOWNLOAD_STARTED, fileSize));
                ReadableByteChannel         rbc  = Channels.newChannel(connection.getInputStream());
                ReadableConsumerByteChannel rcbc = new ReadableConsumerByteChannel(rbc, (b) -> fireEvt(new DownloadEvt(DiscoClient.this, DownloadEvt.DOWNLOAD_PROGRESS, fileSize, b)));
                FileOutputStream            fos  = new FileOutputStream(fileName);
                fos.getChannel().transferFrom(rcbc, 0, Long.MAX_VALUE);
                fos.close();
                rcbc.close();
                rbc.close();
                fireEvt(new DownloadEvt(DiscoClient.this, DownloadEvt.DOWNLOAD_FINISHED, fileSize));
                return true;
            } catch (IOException ex) {
                fireEvt(new DownloadEvt(DiscoClient.this, DownloadEvt.DOWNLOAD_FAILED, 0));
                return false;
            }
        });
    }

    private static final String getDiscoApiUrl() {
        try {
            final String url = PropertyManager.INSTANCE.getString(Constants.PROPERTY_KEY_DISCO_URL);
            return null == url ? Constants.DISCO_API_BASE_URL : url;
        } catch (Exception e) {
            return Constants.DISCO_API_BASE_URL;
        }
    }


    // ******************** Cache *********************************************
    public List<Pkg> getPkgsFromCache(final VersionNumber versionNumber, final Comparison comparison, final List<Distribution> distributions, final List<Architecture> architectures, final List<ArchiveType> archiveTypes,
                                      final PackageType packageType, final List<OperatingSystem> operatingSystems, final List<LibCType> libCTypes, final List<ReleaseStatus> releaseStatus, final List<TermOfSupport> termsOfSupport,
                                      final Bitness bitness, final Boolean javafxBundled, final Boolean directlyDownloadable, final Latest latest, final List<Scope> scopes) {
        List<Pkg> pkgsFound;
        if (Comparison.EQUAL == comparison) {
            switch(latest) {
                case OVERALL:
                    final VersionNumber maxNumber;
                    if (null == versionNumber || versionNumber.getFeature().isEmpty()) {
                        Optional<Pkg> pkgWithMaxVersionNumber = pkgCache.stream()
                                                                        .filter(pkg -> distributions.isEmpty()                    ? (pkg.getDistribution() != null &&
                                                                                                                                     pkg.getDistribution() != Distribution.GRAALVM_CE8 &&
                                                                                                                                     pkg.getDistribution() != Distribution.GRAALVM_CE11 &&
                                                                                                                                     pkg.getDistribution() != Distribution.LIBERICA_NATIVE &&
                                                                                                                                     pkg.getDistribution() != Distribution.MANDREL) : distributions.contains(pkg.getDistribution()))
                                                                        .filter(pkg -> Constants.SCOPE_LOOKUP.get(pkg.getDistribution()).stream().anyMatch(scopes.stream().collect(toSet())::contains))
                                                                        .filter(pkg -> architectures.isEmpty()                    ? pkg.getArchitecture()        != null          : architectures.contains(pkg.getArchitecture()))
                                                                        .filter(pkg -> archiveTypes.isEmpty()                     ? pkg.getArchiveType()         != null          : archiveTypes.contains(pkg.getArchiveType()))
                                                                        .filter(pkg -> operatingSystems.isEmpty()                 ? pkg.getOperatingSystem()     != null          : operatingSystems.contains(pkg.getOperatingSystem()))
                                                                        .filter(pkg -> libCTypes.isEmpty()                        ? pkg.getLibCType()            != null          : libCTypes.contains(pkg.getLibCType()))
                                                                        .filter(pkg -> termsOfSupport.isEmpty()                   ? pkg.getTermOfSupport()       != null          : termsOfSupport.contains(pkg.getTermOfSupport()))
                                                                        .filter(pkg -> PackageType.NONE   == packageType          ? pkg.getPackageType()         != packageType   : pkg.getPackageType()         == packageType)
                                                                        .filter(pkg -> releaseStatus.isEmpty()                    ? pkg.getReleaseStatus()       != null          : releaseStatus.contains(pkg.getReleaseStatus()))
                                                                        .filter(pkg -> Bitness.NONE       == bitness              ? pkg.getBitness()             != bitness       : pkg.getBitness()             == bitness)
                                                                        .filter(pkg -> null               == javafxBundled        ? pkg.isJavaFXBundled()        != null          : pkg.isJavaFXBundled()        == javafxBundled)
                                                                        .filter(pkg -> null               == directlyDownloadable ? pkg.isDirectlyDownloadable() != null          : pkg.isDirectlyDownloadable() == directlyDownloadable)
                                                                        .max(Comparator.comparing(pkg -> pkg.getJavaVersion().getVersionNumber()));
                        if (pkgWithMaxVersionNumber.isPresent()) {
                            maxNumber = pkgWithMaxVersionNumber.get().getJavaVersion().getVersionNumber();
                        } else {
                            maxNumber = versionNumber;
                        }
                    } else {
                        int featureVersion = versionNumber.getFeature().getAsInt();
                        Optional<Pkg> pkgWithMaxVersionNumber = pkgCache.stream()
                                                                        .filter(pkg -> distributions.isEmpty()                    ? pkg.getDistribution()        != null          : distributions.contains(pkg.getDistribution()))
                                                                        .filter(pkg -> Constants.SCOPE_LOOKUP.get(pkg.getDistribution()).stream().anyMatch(scopes.stream().collect(toSet())::contains))
                                                                        .filter(pkg -> architectures.isEmpty()                    ? pkg.getArchitecture()        != null          : architectures.contains(pkg.getArchitecture()))
                                                                        .filter(pkg -> archiveTypes.isEmpty()                     ? pkg.getArchiveType()         != null          : archiveTypes.contains(pkg.getArchiveType()))
                                                                        .filter(pkg -> operatingSystems.isEmpty()                 ? pkg.getOperatingSystem()     != null          : operatingSystems.contains(pkg.getOperatingSystem()))
                                                                        .filter(pkg -> libCTypes.isEmpty()                        ? pkg.getLibCType()            != null          : libCTypes.contains(pkg.getLibCType()))
                                                                        .filter(pkg -> termsOfSupport.isEmpty()                   ? pkg.getTermOfSupport()       != null          : termsOfSupport.contains(pkg.getTermOfSupport()))
                                                                        .filter(pkg -> PackageType.NONE   == packageType          ? pkg.getPackageType()         != packageType   : pkg.getPackageType()         == packageType)
                                                                        .filter(pkg -> releaseStatus.isEmpty()                    ? pkg.getReleaseStatus()       != null          : releaseStatus.contains(pkg.getReleaseStatus()))
                                                                        .filter(pkg -> Bitness.NONE       == bitness              ? pkg.getBitness()             != bitness       : pkg.getBitness()             == bitness)
                                                                        .filter(pkg -> null               == javafxBundled        ? pkg.isJavaFXBundled()        != null          : pkg.isJavaFXBundled()        == javafxBundled)
                                                                        .filter(pkg -> null               == directlyDownloadable ? pkg.isDirectlyDownloadable() != null          : pkg.isDirectlyDownloadable() == directlyDownloadable)
                                                                        .filter(pkg -> featureVersion     == pkg.getJavaVersion().getVersionNumber().getFeature().getAsInt())
                                                                        .max(Comparator.comparing(pkg -> pkg.getJavaVersion().getVersionNumber()));
                        if (pkgWithMaxVersionNumber.isPresent()) {
                            maxNumber = pkgWithMaxVersionNumber.get().getJavaVersion().getVersionNumber();
                        } else {
                            maxNumber = versionNumber;
                        }
                    }
                    pkgsFound = pkgCache.stream()
                                        .filter(pkg -> distributions.isEmpty()                    ? pkg.getDistribution()        != null          : distributions.contains(pkg.getDistribution()))
                                        .filter(pkg -> Constants.SCOPE_LOOKUP.get(pkg.getDistribution()).stream().anyMatch(scopes.stream().collect(toSet())::contains))
                                        .filter(pkg -> architectures.isEmpty()                    ? pkg.getArchitecture()        != null          : architectures.contains(pkg.getArchitecture()))
                                        .filter(pkg -> archiveTypes.isEmpty()                     ? pkg.getArchiveType()         != null          : archiveTypes.contains(pkg.getArchiveType()))
                                        .filter(pkg -> operatingSystems.isEmpty()                 ? pkg.getOperatingSystem()     != null          : operatingSystems.contains(pkg.getOperatingSystem()))
                                        .filter(pkg -> libCTypes.isEmpty()                        ? pkg.getLibCType()            != null          : libCTypes.contains(pkg.getLibCType()))
                                        .filter(pkg -> termsOfSupport.isEmpty()                   ? pkg.getTermOfSupport()       != null          : termsOfSupport.contains(pkg.getTermOfSupport()))
                                        .filter(pkg -> PackageType.NONE   == packageType          ? pkg.getPackageType()         != packageType   : pkg.getPackageType()         == packageType)
                                        .filter(pkg -> releaseStatus.isEmpty()                    ? pkg.getReleaseStatus()       != null          : releaseStatus.contains(pkg.getReleaseStatus()))
                                        .filter(pkg -> Bitness.NONE       == bitness              ? pkg.getBitness()             != bitness       : pkg.getBitness()             == bitness)
                                        .filter(pkg -> null               == javafxBundled        ? pkg.isJavaFXBundled()        != null          : pkg.isJavaFXBundled()        == javafxBundled)
                                        .filter(pkg -> null               == directlyDownloadable ? pkg.isDirectlyDownloadable() != null          : pkg.isDirectlyDownloadable() == directlyDownloadable)
                                        .filter(pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(maxNumber) == 0)
                                        .sorted(Comparator.comparing(Pkg::getDistributionName).reversed().thenComparing(Comparator.comparing((Pkg pkg1) -> pkg1.getJavaVersion().getVersionNumber()).reversed()))
                                        .collect(Collectors.toList());
                    break;
                case PER_DISTRIBUTION:
                    List<Distribution>               distributionsToCheck      = distributions.isEmpty() ? Distribution.getAsList().stream().filter(distribution -> Constants.SCOPE_LOOKUP.get(distribution).stream().anyMatch(scopes.stream().collect(toSet())::contains)).collect(Collectors.toList()) : distributions.stream().filter(distribution -> Constants.SCOPE_LOOKUP.get(distribution).stream().anyMatch(scopes.stream().collect(toSet())::contains)).collect(Collectors.toList());
                    List<Pkg>                        pkgsTmp                   = new ArrayList<>();
                    Map<Distribution, VersionNumber> maxVersionPerDistribution = new ConcurrentHashMap<>();
                    distributionsToCheck.forEach(distro -> {
                        Optional<Pkg> pkgFound = pkgCache.stream()
                                                         .filter(pkg -> pkg.getDistribution().equals(distro))
                                                         .filter(pkg -> architectures.isEmpty()                    ? pkg.getArchitecture()        != null          : architectures.contains(pkg.getArchitecture()))
                                                         .filter(pkg -> archiveTypes.isEmpty()                     ? pkg.getArchiveType()         != null          : archiveTypes.contains(pkg.getArchiveType()))
                                                         .filter(pkg -> operatingSystems.isEmpty()                 ? pkg.getOperatingSystem()     != null          : operatingSystems.contains(pkg.getOperatingSystem()))
                                                         .filter(pkg -> libCTypes.isEmpty()                        ? pkg.getLibCType()            != null          : libCTypes.contains(pkg.getLibCType()))
                                                         .filter(pkg -> termsOfSupport.isEmpty()                   ? pkg.getTermOfSupport()       != null          : termsOfSupport.contains(pkg.getTermOfSupport()))
                                                         .filter(pkg -> PackageType.NONE   == packageType          ? pkg.getPackageType()         != packageType   : pkg.getPackageType()         == packageType)
                                                         .filter(pkg -> releaseStatus.isEmpty()                    ? pkg.getReleaseStatus()       != null          : releaseStatus.contains(pkg.getReleaseStatus()))
                                                         .filter(pkg -> Bitness.NONE       == bitness              ? pkg.getBitness()             != bitness       : pkg.getBitness()             == bitness)
                                                         .filter(pkg -> null               == javafxBundled        ? pkg.isJavaFXBundled()        != null          : pkg.isJavaFXBundled()        == javafxBundled)
                                                         .filter(pkg -> null               == directlyDownloadable ? pkg.isDirectlyDownloadable() != null          : pkg.isDirectlyDownloadable() == directlyDownloadable)
                                                         .max(Comparator.comparing(pkg -> pkg.getJavaVersion().getVersionNumber()));
                        if (pkgFound.isPresent()) { maxVersionPerDistribution.put(distro, pkgFound.get().getJavaVersion().getVersionNumber()); }
                    });

                    distributionsToCheck.forEach(distro -> pkgsTmp.addAll(pkgCache.stream()
                                                                                  .filter(pkg -> pkg.getDistribution().equals(distro))
                                                                                  .filter(pkg -> Constants.SCOPE_LOOKUP.get(pkg.getDistribution()).stream().anyMatch(scopes.stream().collect(toSet())::contains))
                                                                                  .filter(pkg -> architectures.isEmpty()                    ? pkg.getArchitecture()        != null          : architectures.contains(pkg.getArchitecture()))
                                                                                  .filter(pkg -> archiveTypes.isEmpty()                     ? pkg.getArchiveType()         != null          : archiveTypes.contains(pkg.getArchiveType()))
                                                                                  .filter(pkg -> operatingSystems.isEmpty()                 ? pkg.getOperatingSystem()     != null          : operatingSystems.contains(pkg.getOperatingSystem()))
                                                                                  .filter(pkg -> libCTypes.isEmpty()                        ? pkg.getLibCType()            != null          : libCTypes.contains(pkg.getLibCType()))
                                                                                  .filter(pkg -> termsOfSupport.isEmpty()                   ? pkg.getTermOfSupport()       != null          : termsOfSupport.contains(pkg.getTermOfSupport()))
                                                                                  .filter(pkg -> PackageType.NONE   == packageType          ? pkg.getPackageType()         != packageType   : pkg.getPackageType()         == packageType)
                                                                                  .filter(pkg -> releaseStatus.isEmpty()                    ? pkg.getReleaseStatus()       != null          : releaseStatus.contains(pkg.getReleaseStatus()))
                                                                                  .filter(pkg -> Bitness.NONE       == bitness              ? pkg.getBitness()             != bitness       : pkg.getBitness()             == bitness)
                                                                                  .filter(pkg -> null               == javafxBundled        ? pkg.isJavaFXBundled()        != null          : pkg.isJavaFXBundled()        == javafxBundled)
                                                                                  .filter(pkg -> null               == directlyDownloadable ? pkg.isDirectlyDownloadable() != null          : pkg.isDirectlyDownloadable() == directlyDownloadable)
                                                                                  .filter(pkg -> pkg.getJavaVersion().getVersionNumber().equals(maxVersionPerDistribution.get(distro)))
                                                                                  .sorted(Comparator.comparing(Pkg::getDistributionName).reversed().thenComparing(Comparator.comparing((Pkg pkg1) -> pkg1.getJavaVersion().getVersionNumber()).reversed()))
                                                                                  .collect(Collectors.toList())));
                    pkgsFound = pkgsTmp;
                    break;
                case PER_VERSION:
                    pkgsFound = pkgCache.stream()
                                        .filter(pkg -> distributions.isEmpty()                    ? pkg.getDistribution()        != null          : distributions.contains(pkg.getDistribution()))
                                        .filter(pkg -> Constants.SCOPE_LOOKUP.get(pkg.getDistribution()).stream().anyMatch(scopes.stream().collect(toSet())::contains))
                                        .filter(pkg -> architectures.isEmpty()                    ? pkg.getArchitecture()        != null          : architectures.contains(pkg.getArchitecture()))
                                        .filter(pkg -> archiveTypes.isEmpty()                     ? pkg.getArchiveType()         != null          : archiveTypes.contains(pkg.getArchiveType()))
                                        .filter(pkg -> operatingSystems.isEmpty()                 ? pkg.getOperatingSystem()     != null          : operatingSystems.contains(pkg.getOperatingSystem()))
                                        .filter(pkg -> libCTypes.isEmpty()                        ? pkg.getLibCType()            != null          : libCTypes.contains(pkg.getLibCType()))
                                        .filter(pkg -> termsOfSupport.isEmpty()                   ? pkg.getTermOfSupport()       != null          : termsOfSupport.contains(pkg.getTermOfSupport()))
                                        .filter(pkg -> PackageType.NONE   == packageType          ? pkg.getPackageType()         != packageType   : pkg.getPackageType()         == packageType)
                                        .filter(pkg -> releaseStatus.isEmpty()                    ? pkg.getReleaseStatus()       != null          : releaseStatus.contains(pkg.getReleaseStatus()))
                                        .filter(pkg -> Bitness.NONE       == bitness              ? pkg.getBitness()             != bitness       : pkg.getBitness()             == bitness)
                                        .filter(pkg -> null               == javafxBundled        ? pkg.isJavaFXBundled()        != null          : pkg.isJavaFXBundled()        == javafxBundled)
                                        .filter(pkg -> null               == directlyDownloadable ? pkg.isDirectlyDownloadable() != null          : pkg.isDirectlyDownloadable() == directlyDownloadable)
                                        .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getFeature().getAsInt() == versionNumber.getFeature().getAsInt())
                                        .filter(pkg -> pkg.isLatestBuildAvailable())
                                        .sorted(Comparator.comparing(Pkg::getDistributionName).reversed().thenComparing(Comparator.comparing((Pkg pkg1) -> pkg1.getJavaVersion().getVersionNumber()).reversed()))
                                        .collect(Collectors.toList());
                    break;
                case NONE:
                case NOT_FOUND:
                default:
                    pkgsFound = pkgCache.stream()
                                        .filter(pkg -> distributions.isEmpty()                    ? pkg.getDistribution()        != null          : distributions.contains(pkg.getDistribution()))
                                        .filter(pkg -> Constants.SCOPE_LOOKUP.get(pkg.getDistribution()).stream().anyMatch(scopes.stream().collect(toSet())::contains))
                                        .filter(pkg -> null != versionNumber ? pkg.getJavaVersion().getVersionNumber().compareTo(versionNumber) == 0 : null != pkg.getJavaVersion().getVersionNumber())
                                        .filter(pkg -> architectures.isEmpty()                    ? pkg.getArchitecture()        != null          : architectures.contains(pkg.getArchitecture()))
                                        .filter(pkg -> archiveTypes.isEmpty()                     ? pkg.getArchiveType()         != null          : archiveTypes.contains(pkg.getArchiveType()))
                                        .filter(pkg -> operatingSystems.isEmpty()                 ? pkg.getOperatingSystem()     != null          : operatingSystems.contains(pkg.getOperatingSystem()))
                                        .filter(pkg -> libCTypes.isEmpty()                        ? pkg.getLibCType()            != null          : libCTypes.contains(pkg.getLibCType()))
                                        .filter(pkg -> termsOfSupport.isEmpty()                   ? pkg.getTermOfSupport()       != null          : termsOfSupport.contains(pkg.getTermOfSupport()))
                                        .filter(pkg -> PackageType.NONE   == packageType          ? pkg.getPackageType()         != packageType   : pkg.getPackageType()         == packageType)
                                        .filter(pkg -> releaseStatus.isEmpty()                    ? pkg.getReleaseStatus()       != null          : releaseStatus.contains(pkg.getReleaseStatus()))
                                        .filter(pkg -> Bitness.NONE       == bitness              ? pkg.getBitness()             != bitness       : pkg.getBitness()             == bitness)
                                        .filter(pkg -> null               == javafxBundled        ? pkg.isJavaFXBundled()        != null          : pkg.isJavaFXBundled()        == javafxBundled)
                                        .filter(pkg -> null               == directlyDownloadable ? pkg.isDirectlyDownloadable() != null          : pkg.isDirectlyDownloadable() == directlyDownloadable)
                                        .sorted(Comparator.comparing(Pkg::getDistributionName).reversed().thenComparing(Comparator.comparing((Pkg pkg1) -> pkg1.getJavaVersion().getVersionNumber()).reversed()))
                                        .collect(Collectors.toList());
                    if (null != versionNumber) {
                        int featureVersion = versionNumber.getFeature().getAsInt();
                        int interimVersion = versionNumber.getInterim().getAsInt();
                        int updateVersion  = versionNumber.getUpdate().getAsInt();
                        int patchVersion   = versionNumber.getPatch().getAsInt();
                        if (0 != patchVersion) {
                            // e.g. 11.N.N.3
                            pkgsFound = pkgsFound.stream()
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getFeature().getAsInt() == featureVersion)
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getInterim().getAsInt() == interimVersion)
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getUpdate().getAsInt()  == updateVersion)
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getPatch().getAsInt()   == patchVersion)
                                                 .collect(Collectors.toList());
                        } else if (0 != updateVersion) {
                            // e.g. 11.N.2.N
                            pkgsFound = pkgsFound.stream()
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getFeature().getAsInt() == featureVersion)
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getInterim().getAsInt() == interimVersion)
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getUpdate().getAsInt()  == updateVersion)
                                                 .collect(Collectors.toList());
                        } else if (0 != interimVersion) {
                            // e.g. 11.1.N.N
                            pkgsFound = pkgsFound.stream()
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getFeature().getAsInt() == featureVersion)
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getInterim().getAsInt() == interimVersion)
                                                 .collect(Collectors.toList());
                        } else {
                            // e.g. 11.N.N.N
                            pkgsFound = pkgsFound.stream()
                                                 .filter(pkg -> pkg.getJavaVersion().getVersionNumber().getFeature().getAsInt() == featureVersion)
                                                 .collect(Collectors.toList());
                        }
                    }
                    break;
            }
        } else {
            VersionNumber  minVersionNumber;
            VersionNumber  maxVersionNumber;
            Predicate<Pkg> greaterCheck;
            Predicate<Pkg> smallerCheck;
            Queue<MajorVersion> majorVersions = majorVersionCache.isEmpty() ? getAllMajorVersions(true) : majorVersionCache;
            switch (comparison) {
                case EQUAL:
                    minVersionNumber = versionNumber;
                    maxVersionNumber = versionNumber;
                    greaterCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(minVersionNumber) >= 0;
                    smallerCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(maxVersionNumber) <= 0;
                    break;
                case LESS_THAN:
                    minVersionNumber = new VersionNumber(6);
                    maxVersionNumber = versionNumber;
                    greaterCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(minVersionNumber) >= 0;
                    smallerCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(maxVersionNumber) < 0;
                    break;
                case LESS_THAN_OR_EQUAL:
                    minVersionNumber = new VersionNumber(6);
                    maxVersionNumber = versionNumber;
                    greaterCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(minVersionNumber) >= 0;
                    smallerCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(maxVersionNumber) <= 0;
                    break;
                case GREATER_THAN:
                    minVersionNumber = versionNumber;
                    maxVersionNumber = new VersionNumber(majorVersions.peek().getAsInt());
                    greaterCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(minVersionNumber) > 0;
                    smallerCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(maxVersionNumber) <= 0;
                    break;
                case GREATER_THAN_OR_EQUAL:
                    minVersionNumber = versionNumber;
                    maxVersionNumber = new VersionNumber(majorVersions.peek().getAsInt());
                    greaterCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(minVersionNumber) >= 0;
                    smallerCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(maxVersionNumber) <= 0;
                    break;
                default:
                    minVersionNumber = new VersionNumber(6);
                    maxVersionNumber = new VersionNumber(majorVersions.peek().getAsInt());
                    greaterCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(minVersionNumber) >= 0;
                    smallerCheck     = pkg -> pkg.getJavaVersion().getVersionNumber().compareTo(maxVersionNumber) <= 0;
                    break;
            }

            pkgsFound = pkgCache.stream()
                                .filter(pkg -> distributions.isEmpty()                  ? pkg.getDistribution()        != null          : distributions.contains(pkg.getDistribution()))
                                .filter(pkg -> Constants.SCOPE_LOOKUP.get(pkg.getDistribution()).stream().anyMatch(scopes.stream().collect(toSet())::contains))
                                .filter(pkg -> architectures.isEmpty()                  ? pkg.getArchitecture()        != null          : architectures.contains(pkg.getArchitecture()))
                                .filter(pkg -> archiveTypes.isEmpty()                   ? pkg.getArchiveType()         != null          : archiveTypes.contains(pkg.getArchiveType()))
                                .filter(pkg -> operatingSystems.isEmpty()               ? pkg.getOperatingSystem()     != null          : operatingSystems.contains(pkg.getOperatingSystem()))
                                .filter(pkg -> libCTypes.isEmpty()                      ? pkg.getLibCType()            != null          : libCTypes.contains(pkg.getLibCType()))
                                .filter(pkg -> termsOfSupport.isEmpty()                 ? pkg.getTermOfSupport()       != null          : termsOfSupport.contains(pkg.getTermOfSupport()))
                                .filter(pkg -> PackageType.NONE == packageType          ? pkg.getPackageType()         != packageType   : pkg.getPackageType()         == packageType)
                                .filter(pkg -> releaseStatus.isEmpty()                  ? pkg.getReleaseStatus()       != null          : releaseStatus.contains(pkg.getReleaseStatus()))
                                .filter(pkg -> Bitness.NONE     == bitness              ? pkg.getBitness()             != bitness       : pkg.getBitness()             == bitness)
                                .filter(pkg -> null             == javafxBundled        ? pkg.isJavaFXBundled()        != null          : pkg.isJavaFXBundled()        == javafxBundled)
                                .filter(pkg -> null             == directlyDownloadable ? pkg.isDirectlyDownloadable() != null          : pkg.isDirectlyDownloadable() == directlyDownloadable)
                                .filter(greaterCheck)
                                .filter(smallerCheck)
                                .sorted(Comparator.comparing(Pkg::getDistributionName).reversed().thenComparing(Comparator.comparing((Pkg pkg1) -> pkg1.getJavaVersion().getVersionNumber()).reversed()))
                                .collect(Collectors.toList());
        }
        return pkgsFound;
    }



    // ******************** Event Handling ************************************
    public final void setOnEvt(final EvtType<? extends Evt> type, final EvtObserver observer) {
        if (!observers.keySet().contains(type.getName())) { observers.put(type.getName(), new CopyOnWriteArrayList<>()); }
        if (!observers.get(type.getName()).contains(observer)) { observers.get(type.getName()).add(observer); }
    }
    public final void removeOnEvt(final EvtType<? extends Evt> type, final EvtObserver observer) {
        if (!observers.keySet().contains(type.getName())) { return; }
        if (observers.get(type.getName()).contains(observer)) { observers.get(type.getName()).remove(observer); }
    }
    public final void removeAllObservers() { observers.entrySet().forEach(entry -> entry.getValue().clear()); }

    public final void fireEvt(final Evt evt) {
        final EvtType<? extends Evt> type = evt.getEvtType();
        if (observers.containsKey(type.getName())) {
            observers.get(type.getName()).forEach(observer -> observer.handle(evt));
        }
        if (observers.keySet().contains(DCEvt.ANY.getName())) {
            observers.get(DCEvt.ANY.getName()).forEach(observer -> observer.handle(evt));
        }
    }
}
