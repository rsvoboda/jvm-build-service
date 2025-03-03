package com.redhat.hacbs.artifactcache.services;

import java.net.URI;
import java.util.*;

import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.artifactcache.services.client.maven.MavenClient;
import com.redhat.hacbs.artifactcache.services.client.s3.S3RepositoryClient;

import io.quarkus.logging.Log;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Class that consumes the repository config and creates the runtime representation of the repositories
 */
class BuildPolicyManager {

    private static final String STORE = "store.";
    private static final String URL = ".url";
    private static final String TYPE = ".type";
    private static final String BUCKET = ".bucket";
    private static final String PREFIXES = ".prefixes";
    public static final String STORE_LIST = ".store-list";
    public static final String BUILD_POLICY = "build-policy.";

    @Inject
    S3Client s3Client;

    @Produces
    @Singleton
    Map<String, BuildPolicy> createBuildPolicies(@ConfigProperty(name = "build-policies") Set<String> buildPolicies,
            Config config) {
        Map<String, BuildPolicy> ret = new HashMap<>();
        Map<String, Repository> remoteStores = new HashMap<>();
        for (String policy : buildPolicies) {
            Optional<String> stores = config.getOptionalValue(BUILD_POLICY + policy + STORE_LIST, String.class);
            if (stores.isEmpty()) {
                Log.warnf("No config for build policy %s, ignoring", policy);
                continue;
            }
            List<Repository> repositories = new ArrayList<>();
            for (var store : stores.get().split(",")) {
                Repository existing = remoteStores.get(store);
                if (existing != null) {
                    repositories.add(existing);
                } else {
                    existing = createRepository(config, store);
                    if (existing != null) {
                        repositories.add(existing);
                        remoteStores.put(store, existing);
                    }
                }
            }
            if (!repositories.isEmpty()) {
                ret.put(policy, new BuildPolicy(repositories));
            } else {
                Log.warnf("No configured repositories for build policy %s, ignoring", policy);
            }
        }
        if (ret.isEmpty()) {
            throw new IllegalStateException("No configured build policies present, repository cache cannot function");
        }
        return ret;
    }

    private Repository createRepository(Config config, String repo) {
        Optional<URI> uri = config.getOptionalValue(STORE + repo + URL, URI.class);
        Optional<RepositoryType> optType = config.getOptionalValue(STORE + repo + TYPE, RepositoryType.class);

        if (uri.isPresent() && optType.orElse(RepositoryType.MAVEN2) == RepositoryType.MAVEN2) {
            Log.infof("Repository %s added with URI %s", repo, uri.get());
            RepositoryClient client = MavenClient.of(repo, uri.get());
            return new Repository(repo, uri.get().toASCIIString(), RepositoryType.MAVEN2, client);
        } else if (optType.orElse(null) == RepositoryType.S3) {
            //we need to get the config

            Optional<String> bucket = config.getOptionalValue(STORE + repo + BUCKET, String.class);
            if (bucket.isPresent()) {
                String[] prefixes = config.getOptionalValue(STORE + repo + PREFIXES, String.class).orElse("default").split(",");
                RepositoryClient client = new S3RepositoryClient(s3Client, Arrays.asList(prefixes), bucket.get());
                return new Repository(repo, "s3://" + bucket + Arrays.toString(prefixes), RepositoryType.S3, client);
            } else {
                Log.warnf("S3 Repository %s was listed but has no bucket configured and will be ignored", repo);
            }
        }
        Log.warnf("Repository %s was listed but has no config and will be ignored", repo);
        return null;
    }
}
