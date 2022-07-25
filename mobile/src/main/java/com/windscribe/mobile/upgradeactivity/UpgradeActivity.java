/*
 * Copyright (c) 2021 Windscribe Limited.
 */

package com.windscribe.mobile.upgradeactivity;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.android.billingclient.api.BillingClient.SkuType.INAPP;
import static com.windscribe.vpn.constants.ExtraConstants.PROMO_EXTRA;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.amazon.device.iap.model.Product;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.windscribe.mobile.R;
import com.windscribe.mobile.base.BaseActivity;
import com.windscribe.mobile.confirmemail.ConfirmActivity;
import com.windscribe.mobile.custom_view.CustomDialog;
import com.windscribe.mobile.custom_view.ErrorFragment;
import com.windscribe.mobile.custom_view.ProgressFragment;
import com.windscribe.mobile.di.ActivityModule;
import com.windscribe.mobile.email.AddEmailActivity;
import com.windscribe.mobile.welcome.WelcomeActivity;
import com.windscribe.mobile.windscribe.WindscribeActivity;
import com.windscribe.vpn.Windscribe;
import com.windscribe.vpn.api.response.PushNotificationAction;
import com.windscribe.vpn.billing.AmazonBillingManager;
import com.windscribe.vpn.billing.BillingFragmentCallback;
import com.windscribe.vpn.billing.GoogleBillingManager;
import com.windscribe.vpn.billing.PurchaseState;
import com.windscribe.vpn.billing.WindscribeInAppProduct;
import com.windscribe.vpn.constants.NetworkKeyConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import javax.inject.Inject;

public class UpgradeActivity extends BaseActivity
        implements UpgradeView
        , BillingFragmentCallback {

    public enum BillingType {
        Google("android"), Amazon("amazon");

        private final String value;

        BillingType(String value) {
            this.value = value;
        }

        @SuppressWarnings("unused")
        public String getValue() {
            return value;
        }
    }

    private static final String TAG = "upgrade_a";

    @Inject
    CustomDialog mUpgradeDialog;

    @Inject
    UpgradePresenter mUpgradePresenter;

    private boolean bBillingProcessFinished = false;

    private BillingType billingType = BillingType.Google;

    private AmazonBillingManager mAmazonBillingManager;

    private GoogleBillingManager mGoogleBillingManager;

    private final Logger mUpgradeLog = LoggerFactory.getLogger(TAG);

    private SkuDetails selectedSkuDetails;

    private Boolean upgradingFromWebsite = false;

    public static Intent getStartIntent(Context context) {
        return new Intent(context, UpgradeActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setActivityModule(new ActivityModule(this, this)).inject(this);
        setContentLayout(R.layout.activity_upgrade, false);

        mUpgradeLog.info("OnCreate: Upgrade Activity");
        showProgressBar("Loading Billing Plans...");

        if (getIntent().hasExtra(PROMO_EXTRA)) {
            PushNotificationAction pushNotificationAction = (PushNotificationAction) getIntent()
                    .getSerializableExtra(PROMO_EXTRA);
                mUpgradePresenter.setPushNotificationAction(pushNotificationAction);
        }
        setBillingType();
        if (billingType == BillingType.Amazon) {
            mAmazonBillingManager = Windscribe.getAppContext().getAmazonBillingManager();
            getLifecycle().addObserver(mAmazonBillingManager);
            initAmazonBillingLifecycleListeners();
        } else {
            mGoogleBillingManager = Windscribe.getAppContext().getBillingManager();
            getLifecycle().addObserver(mGoogleBillingManager);
            initBillingLifecycleListeners();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(upgradingFromWebsite){
            goBackToMainActivity();
        }else{
            if (billingType == BillingType.Google) {
                mUpgradePresenter.checkBillingProcessStatus();
            }
            mUpgradePresenter.setLayoutFromApiSession();
        }
    }

    @Override
    protected void onDestroy() {
        mUpgradeDialog.cancel();
        mUpgradePresenter.onDestroy();
        super.onDestroy();
    }

    @Override
    public BillingType getBillingType() {
        return billingType;
    }

    @Override
    public void getProducts(List<String> skuList) {
        mAmazonBillingManager.getProducts(skuList);
    }

    @Override
    public void goBackToMainActivity() {
        mUpgradeLog.info("Going back to previous activity...");
        onBackPressed();
    }

    @Override
    public void goToAddEmail() {
        Intent startIntent = new Intent(this, AddEmailActivity.class);
        startIntent.putExtra("goToHomeAfterFinish", false);
        startActivity(startIntent);
    }

    @Override
    public void goToConfirmEmail() {
        Intent startIntent = new Intent(this, ConfirmActivity.class);
        startActivity(startIntent);
    }

    @Override
    public void gotToClaimAccount() {
        Intent startIntent = WelcomeActivity.getStartIntent(this);
        startIntent.putExtra("startFragmentName", "AccountSetUp");
        startActivity(startIntent);
        finish();
    }

    @Override
    public void hideProgressBar() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.cl_upgrade);
        if (fragment instanceof ProgressFragment) {
            getSupportFragmentManager().beginTransaction().remove(fragment).commitNow();
        }
    }

    @Override
    public boolean isBillingProcessFinished() {
        return bBillingProcessFinished;
    }

    @Override
    public void onContinuePlanClick(@Nullable SkuDetails selectedSkuDetails) {
        if(selectedSkuDetails!=null){
            mUpgradeLog.info("User clicked on plan item...");
            String regionPlanUrl = mUpgradePresenter.regionalPlanIfAvailable(selectedSkuDetails.getSku());
            if(regionPlanUrl!=null){
                mUpgradePresenter.onRegionalPlanSelected(regionPlanUrl);
            }else{
                this.selectedSkuDetails = selectedSkuDetails;
                mUpgradePresenter.onMonthlyItemClicked(selectedSkuDetails);
            }
        }
    }

    @Override
    public void openUrlInBrowser(String url) {
        upgradingFromWebsite = true;
        openURLInBrowser(url);
    }

    @Override
    public void onContinuePlanClick(final Product selectedSku) {
        mUpgradePresenter.onContinuePlanClick(selectedSku);
    }

    @Override
    public void onPurchaseCancelled() {
        mUpgradeLog.info("Setting billing process finished...");
        bBillingProcessFinished = true;
    }

    @Override
    public void onPurchaseSuccessful(@Nullable List<Purchase> purchases) {
        if (purchases != null) {
            Purchase purchase = purchases.get(0);
            if (selectedSkuDetails != null && selectedSkuDetails.getType().equals(INAPP)) {
                mGoogleBillingManager.InAppConsume(purchase);
            } else {
                mGoogleBillingManager.subscriptionConsume(purchase);
            }
        }
    }

    @Override
    public void onRestorePurchaseClick() {
        mUpgradePresenter.restorePurchase();
    }

    @Override
    public void onTenGbFreeClick() {
        mUpgradeLog.info("User clicked on continue free...");
        mUpgradePresenter.onContinueFreeClick();
    }

    @Override
    public void onTermPolicyClick() {
        openURLInBrowser(NetworkKeyConstants.getWebsiteLink(NetworkKeyConstants.URL_PRIVACY));
    }

    @Override
    public void querySkuDetails(List<String> subs, boolean isSub) {
        mUpgradeLog.info("Querying sku details..." + isSub);
        mGoogleBillingManager.querySkuDetailsAsync(subs, isSub);
    }

    @Override
    public void restorePurchase() {
        mAmazonBillingManager.getPurchaseHistory();
    }

    @Override
    public void setBillingProcessStatus(boolean bProcessFinished) {
        bBillingProcessFinished = bProcessFinished;
    }

    @Override
    public void setEmailStatus(boolean isEmailAdded, boolean isEmailConfirmed) {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.cl_upgrade);
        if (fragment instanceof PlansFragment) {
            ((PlansFragment) fragment).setEmailStatus(isEmailAdded, isEmailConfirmed);
        }
    }

    @Override
    public void showBillingDialog(final WindscribeInAppProduct windscribeInAppProduct, final boolean isEmailAdded,
            final boolean isEmailConfirmed) {
        PlansFragment.newInstance()
                .add(this, windscribeInAppProduct, R.id.cl_upgrade, false, isEmailAdded, isEmailConfirmed);
    }

    @Override
    public void showBillingErrorDialog(String errorMessage) {
        hideProgressBar();
        ErrorFragment.getInstance().add(errorMessage, this, R.id.cl_upgrade, false);
    }

    @Override
    public void showProgressBar(final String progressHeaderText) {
        runOnUiThread(() -> {
            Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.cl_upgrade);
            if (!(fragment instanceof ProgressFragment)) {
                ProgressFragment.getInstance().add(progressHeaderText, UpgradeActivity.this, R.id.cl_upgrade, false);
                getSupportFragmentManager().executePendingTransactions();
            }
            if (fragment instanceof ProgressFragment) {
                ((ProgressFragment) fragment).updateProgressStatus(progressHeaderText);
            }
        });

    }

    @Override
    public void showToast(String toastText) {
        mUpgradeLog.info("Showing toast to the user...");
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void startPurchaseFlow(SkuDetails skuDetails, String accountID) {
        final BillingFlowParams.Builder builder = BillingFlowParams.newBuilder().setSkuDetails(skuDetails);
        if (accountID != null) {
            builder.setObfuscatedAccountId(accountID);
        }
        mUpgradeLog.info("Launching billing flow...");
        mUpgradePresenter.setPurchaseFlowState(PurchaseState.IN_PROCESS);
        mGoogleBillingManager.launchBillingFlow(this, builder.build());
    }

    @Override
    public void startPurchaseFlow(final Product product) {
        mAmazonBillingManager.launchPurchaseFlow(product);
    }

    @Override
    public void startSignUpActivity() {
        Intent startIntent = WelcomeActivity.getStartIntent(this);
        startIntent.putExtra("startFragmentName", "AccountSetUp");
        startActivity(startIntent);
        finish();
    }

    @Override
    public void startWindscribeActivity() {
        Intent startIntent = new Intent(this, WindscribeActivity.class);
        startIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_NEW_TASK);
        startActivity(startIntent);
        finish();
    }

    void initAmazonBillingLifecycleListeners() {
        mAmazonBillingManager.onBillingSetUpSuccess.observe(this, code -> {
            mUpgradeLog.info("Billing client connected successfully...");
            mUpgradePresenter.onBillingSetupSuccessful();
        });

        mAmazonBillingManager.onProductsResponseSuccess
                .observe(this, products -> mUpgradePresenter.onProductDataResponse(products));

        mAmazonBillingManager.onProductsResponseFailure
                .observe(this, requestStatus -> mUpgradePresenter.onProductResponseFailure());

        mAmazonBillingManager.onPurchaseResponseSuccess
                .observe(this, purchaseResponse -> mUpgradePresenter.onPurchaseResponse(purchaseResponse));

        mAmazonBillingManager.onPurchaseResponseFailure
                .observe(this, requestStatus -> mUpgradePresenter.onPurchaseResponseFailure(requestStatus));

        mAmazonBillingManager.onAmazonPurchaseHistorySuccess
                .observe(this, purchases -> mUpgradePresenter.onAmazonPurchaseHistorySuccess(purchases));

        mAmazonBillingManager.onAmazonPurchaseHistoryError
                .observe(this, error -> mUpgradePresenter.onAmazonPurchaseHistoryError(error));
    }

    void initBillingLifecycleListeners() {
        mGoogleBillingManager.onBillingSetUpSuccess.observe(this, code -> {
            mUpgradeLog.info("Billing client connected successfully...");
            mUpgradePresenter.onBillingSetupSuccessful();
        });
        mGoogleBillingManager.onBillingSetupFailure.observe(this, code -> {
            mUpgradeLog.info("Billing client set up failure...");
            mUpgradePresenter.onBillingSetupFailed(code);
        });

        mGoogleBillingManager.onProductConsumeSuccess.observe(this, purchase -> {
            mUpgradeLog.info("Product consumption successful...");
            showToast(getResources().getString(R.string.purchase_successful));
            mUpgradePresenter.onPurchaseConsumed(purchase);
        });

        mGoogleBillingManager.onProductConsumeFailure.observe(this, customPurchase -> {
            mUpgradeLog.debug("Product consumption failed...");
            mUpgradePresenter.onConsumeFailed(customPurchase.getResponseCode(), customPurchase.getPurchase());
        });

        mGoogleBillingManager.purchaseUpdateEvent.observe(this, customPurchases -> {
            mUpgradeLog.info("Purchase updated...");
            mUpgradePresenter.onPurchaseUpdated(customPurchases.getResponseCode(), customPurchases.getPurchase());
        });
        mGoogleBillingManager.querySkuDetailEvent.observe(this, customSkuDetails -> mUpgradePresenter
                .onSkuDetailsReceived(customSkuDetails.getBillingResult().getResponseCode(),
                        customSkuDetails.getSkuDetails()));

    }

    private void setBillingType() {
        PackageManager pkgManager = getPackageManager();
        String installerPackageName = pkgManager.getInstallerPackageName(getPackageName());
        if (installerPackageName != null && installerPackageName.startsWith("com.amazon")) {
            billingType = BillingType.Amazon;
        } else {
            billingType = BillingType.Google;
        }
    }
}
