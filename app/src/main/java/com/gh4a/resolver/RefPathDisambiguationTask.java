package com.gh4a.resolver;

import android.content.Intent;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.FragmentActivity;
import android.text.TextUtils;
import android.util.Pair;

import com.gh4a.Gh4Application;
import com.gh4a.activities.FileViewerActivity;
import com.gh4a.activities.RepositoryActivity;
import com.gh4a.utils.ApiHelpers;
import com.meisolsson.githubsdk.model.Branch;
import com.meisolsson.githubsdk.model.Page;
import com.meisolsson.githubsdk.service.repositories.RepositoryBranchService;
import com.meisolsson.githubsdk.service.repositories.RepositoryService;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

public class RefPathDisambiguationTask extends UrlLoadTask {
    private static final Pattern SHA1_PATTERN = Pattern.compile("[a-z0-9]{40}");

    @VisibleForTesting
    protected final String mRepoOwner;
    @VisibleForTesting
    protected final String mRepoName;
    @VisibleForTesting
    protected final String mRefAndPath;
    @VisibleForTesting
    protected final int mInitialPage;
    @VisibleForTesting
    protected final String mFragment;
    @VisibleForTesting
    protected final boolean mGoToFileViewer;

    public RefPathDisambiguationTask(FragmentActivity activity, String repoOwner,
            String repoName, String refAndPath, int initialPage) {
        super(activity);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mRefAndPath = refAndPath;
        mInitialPage = initialPage;
        mFragment = null;
        mGoToFileViewer = false;
    }

    public RefPathDisambiguationTask(FragmentActivity activity, String repoOwner,
            String repoName, String refAndPath, String fragment) {
        super(activity);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mRefAndPath = refAndPath;
        mFragment = fragment;
        mInitialPage = -1;
        mGoToFileViewer = true;
    }

    @Override
    protected Intent run() throws Exception {
        Pair<String, String> refAndPath = resolve();
        if (refAndPath == null) {
            return null;
        }

        if (mGoToFileViewer && refAndPath.second != null) {
            // parse line numbers from fragment
            int highlightStart = -1, highlightEnd = -1;
            // Line numbers are encoded either in the form #L12 or #L12-14
            if (mFragment != null && mFragment.startsWith("L")) {
                try {
                    int dashPos = mFragment.indexOf("-L");
                    if (dashPos > 0) {
                        highlightStart = Integer.valueOf(mFragment.substring(1, dashPos));
                        highlightEnd = Integer.valueOf(mFragment.substring(dashPos + 2));
                    } else {
                        highlightStart = Integer.valueOf(mFragment.substring(1));
                    }
                } catch (NumberFormatException e) {
                    // ignore
                }
            }

            return FileViewerActivity.makeIntentWithHighlight(mActivity,
                    mRepoOwner, mRepoName, refAndPath.first, refAndPath.second,
                    highlightStart, highlightEnd);
        } else if (!mGoToFileViewer) {
            return RepositoryActivity.makeIntent(mActivity,
                    mRepoOwner, mRepoName, refAndPath.first, refAndPath.second, mInitialPage);
        }

        return null;
    }

    // returns ref, path
    private Pair<String, String> resolve() throws Exception {
        final Gh4Application app = Gh4Application.get();
        final RepositoryBranchService branchService =
                app.getGitHubService(RepositoryBranchService.class);
        final RepositoryService repoService = app.getGitHubService(RepositoryService.class);

        // try branches first
        List<Branch> branches = ApiHelpers.Pager.fetchAllPages(new ApiHelpers.Pager.PageProvider<Branch>() {
            @Override
            public Page<Branch> providePage(long page) throws IOException {
                return ApiHelpers.throwOnFailure(
                        branchService.getBranches(mRepoOwner, mRepoName, page).blockingGet());
            }
        });
        for (Branch branch : branches) {
            if (TextUtils.equals(mRefAndPath, branch.name())) {
                return Pair.create(branch.name(), null);
            } else {
                String nameWithSlash = branch.name() + "/";
                if (mRefAndPath.startsWith(nameWithSlash)) {
                    return Pair.create(branch.name(),
                            mRefAndPath.substring(nameWithSlash.length()));
                }
            }
        }

        if (mActivity.isFinishing()) {
            return null;
        }

        // and tags second
        List<Branch> tags = ApiHelpers.Pager.fetchAllPages(new ApiHelpers.Pager.PageProvider<Branch>() {
            @Override
            public Page<Branch> providePage(long page) throws IOException {
                return ApiHelpers.throwOnFailure(
                        repoService.getTags(mRepoOwner, mRepoName, page).blockingGet());
            }
        });
        for (Branch tag : tags) {
            if (TextUtils.equals(mRefAndPath, tag.name())) {
                return Pair.create(tag.name(), null);
            } else {
                String nameWithSlash = tag.name() + "/";
                if (mRefAndPath.startsWith(nameWithSlash)) {
                    return Pair.create(tag.name(),
                            mRefAndPath.substring(nameWithSlash.length()));
                }
            }
        }

        // at this point, the first item may still be a SHA1 - check with a simple regex
        int slashPos = mRefAndPath.indexOf('/');
        String potentialSha = slashPos > 0 ? mRefAndPath.substring(0, slashPos) : mRefAndPath;
        if (SHA1_PATTERN.matcher(potentialSha).matches()) {
            return Pair.create(potentialSha,
                    slashPos > 0 ? mRefAndPath.substring(slashPos + 1) : "");
        }

        return null;
    }
}