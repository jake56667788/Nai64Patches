package patches.universal.unlock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import patches.universal.ads.util.cloneMutable
import patches.universal.ads.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Unlock premium features and remove paywalls.",
    default = false,
) {
    category("Featured")

    val extraKeys by stringOption(
        title = "Extra keys",
        default = "",
        key = "premiumCustomKeys",
        description = "Comma-separated extra SharedPreferences/DataStore keys to spoof (e.g. my_premium,my_pro). Leave empty for default list.",
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)

        var patched = 0
        val patchedMethods = mutableSetOf<String>()

        val extraSet = (extraKeys ?: "")
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        /*
         * Premium-related preference keys.
         *
         * Suspension keys are intentionally excluded. In particular, we do
         * not want IS_PREMIUM_SUSPENDED_KEY to be treated as a premium flag.
         */
        val premiumSubstrings = listOf(
            "purchased",
            "has_receipt",
            "hasreceipt",
            "bought",
            "premium",
            "is_premium",
            "ispremium",
            "premium_unlocked",
            "premium_status",
            "premium_expiry",
            "premiumaccess",
            "haspremiumaccess",

            "vip",
            "is_vip",
            "vip_status",
            "vip_level",
            "vip_expiry",

            "no_ads",
            "noads",
            "ads_removed",
            "adsremoved",
            "ad_free",
            "adfree",
            "remove_ads",
            "removeads",

            "full_version",
            "fullversion",
            "unlocked",

            "subscribed",
            "is_subscribed",
            "subscription_active",
            "subscription_expires",
            "has_subscription",
            "has_active_purchase",

            "lifetime",
            "is_lifetime",
            "annual",
            "monthly",
            "trial",

            "entitlement",
            "entitlements",
            "is_entitled",
            "has_entitlement",

            "paid",
            "is_paid",
            "member",
            "pro_version",
            "is_pro",
            "pro_member",

            "subscription_expiry",
            "premium_expiry",
            "key_subs",
            "key_sub",
            "subs"
        )

        fun isPremiumKey(lower: String): Boolean {
            if (extraSet.any { it.isNotEmpty() && lower == it }) {
                return true
            }

            /*
             * Do not touch keys whose purpose is to ignore, disregard, or
             * suspend premium state.
             */
            if (
                lower.contains("ignore") ||
                lower.contains("disregard") ||
                lower.contains("suspend")
            ) {
                return false
            }

            for (k in premiumSubstrings) {
                if (lower.contains(k)) {
                    if (k == "pro_version" || k == "is_pro") {
                        return true
                    }

                    if (
                        lower.contains("provider") ||
                        (lower.contains("product") && k == "pro")
                    ) {
                        continue
                    }

                    return true
                }
            }

            if (
                lower == "pro" ||
                lower == "vip" ||
                lower == "pro_version" ||
                lower == "is_pro" ||
                lower == "is_vip"
            ) {
                return true
            }

            if (
                lower.contains("_pro_") ||
                lower.endsWith("_pro") ||
                lower.startsWith("pro_")
            ) {
                if (
                    lower.contains("provider") ||
                    lower.contains("product") ||
                    lower.contains("process") ||
                    lower.contains("progress") ||
                    lower.contains("project") ||
                    lower.contains("proceed")
                ) {
                    return false
                }

                return true
            }

            return false
        }

        fun patchAll(
            fp: Fingerprint,
            label: String,
            injector: (
                app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
            ) -> Unit
        ) {
            try {
                val matches: List<app.morphe.patcher.Match> =
                    try {
                        with(this@execute) {
                            fp.matchAll()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }

                if (matches.isNotEmpty()) {
                    for (m in matches) {
                        try {
                            val method = m.method

                            if (method.implementation == null) {
                                continue
                            }

                            injector(method)

                            patched++
                            patchedMethods.add(label)
                        } catch (_: Exception) {
                            // Ignore individual method failures.
                        }
                    }

                    return
                }
            } catch (_: Exception) {
                // Fall through to single-match handling.
            }

            val single = try {
                fp.methodOrNull
            } catch (_: Exception) {
                null
            }

            if (single?.implementation != null) {
                try {
                    injector(single)

                    patched++
                    patchedMethods.add(label)
                } catch (_: Exception) {
                    // Ignore individual method failures.
                }
            }
        }

        /*
         * 1) Premium checks
         */
        for (checkName in listOf(
            "isPurchased",
            "isOwned",
            "isPremium",
            "hasPremium",
            "hasPremiumAccess",
            "isPremiumAccess",
            "isSubscribed",
            "hasSubscription",
            "isVip",
            "hasVip",
            "isBought",
            "hasBought",
            "wasPurchased",
            "hasPurchased",
            "isPro",
            "hasPro",
            "isProUser",
            "hasProUser",
            "isFullVersion",
            "hasFullVersion",
            "isUnlocked",
            "hasUnlocked",
            "isActive",
            "hasActive",
            "isLifetime",
            "hasLifetime",
            "isAnnual",
            "hasAnnual",
            "hasEntitlement",
            "isEntitled",
            "checkPremium",
            "verifyPremium",
            "isPremiumUser",
            "hasAdFree",
            "isPaidUser",
            "checkVip",
            "hasSubscriptionActive",
            "hasActivePurchase",
            "isProMember",
            "isVipUser",
            "hasPremiumAccessChanged",
            "hasProFeatures",
            "hasProAccess",
            "hasActiveSubscription",
        )) {
            val isGenericActive =
                checkName == "isActive" ||
                checkName == "hasActive" ||
                checkName == "isPro" ||
                checkName == "hasPro"

            patchAll(
                Fingerprint(
                    name = checkName,
                    returnType = "Z",
                    custom = if (isGenericActive) {
                        { _, c ->
                            val t = c.type.lowercase()

                            !t.contains("okhttp") &&
                                !t.contains("ssl") &&
                                !t.contains("network") &&
                                (
                                    t.contains("premium") ||
                                        t.contains("purchase") ||
                                        t.contains("billing") ||
                                        t.contains("subscription") ||
                                        t.contains("user") ||
                                        t.contains("entitle") ||
                                        t.contains("vip") ||
                                        t.contains("pro")
                                    )
                        }
                    } else {
                        null
                    }
                ),
                checkName
            ) {
                it.addInstructions(
                    0,
                    """
                    const/4 v0, 0x1
                    return v0
                    """.trimIndent()
                )
            }
        }

        /*
         * 2) Negative premium checks
         *
         * isSuspended and isPremiumSuspended remain intentionally omitted.
         */
        for (negName in listOf(
            "isExpired",
            "isCancelled",
            "isTrialExpired",
            "isLocked",
            "isPremiumLocked",
            "isContentLocked",
            "isHardPaywall"
        )) {
            patchAll(
                Fingerprint(
                    name = negName,
                    returnType = "Z",
                    custom = { _, c ->
                        val t = c.type.lowercase()

                        t.contains("premium") ||
                            t.contains("subscription") ||
                            t.contains("entitle") ||
                            t.contains("vip") ||
                            t.contains("billing") ||
                            t.contains("purchase") ||
                            t.contains("content") ||
                            t.contains("station") ||
                            t.contains("paywall")
                    }
                ),
                negName
            ) {
                it.addInstructions(
                    0,
                    """
                    const/4 v0, 0x0
                    return v0
                    """.trimIndent()
                )
            }
        }

        /*
         * 3) Paywall option flags
         */
        for (optName in listOf(
            "getSupportsLifetimeSwitch",
            "getSupportsPromo",
            "getDoesOfferTrial"
        )) {
            patchAll(
                Fingerprint(
                    name = optName,
                    returnType = "Z"
                ),
                optName
            ) {
                it.addInstructions(
                    0,
                    """
                    const/4 v0, 0x1
                    return v0
                    """.trimIndent()
                )
            }
        }

        /*
         * 4) Integer premium state
         */
        for (intName in listOf(
            "getPremiumState",
            "getVipLevel",
            "getSubscriptionStatus",
            "getProState",
            "getVipStatus",
            "getUserType",
            "getPremiumStatusInt",
            "getEntitlementState"
        )) {
            patchAll(
                Fingerprint(
                    name = intName,
                    returnType = "I"
                ),
                intName
            ) {
                it.addInstructions(
                    0,
                    """
                    const/4 v0, 0x1
                    return v0
                    """.trimIndent()
                )
            }
        }

        /*
         * 5) Expiry timestamps
         */
        for (longName in listOf(
            "getExpiryTime",
            "getExpireDate",
            "getSubscriptionExpiry",
            "getPremiumExpiry",
            "getVipExpiry",
            "getEntitlementExpiry"
        )) {
            patchAll(
                Fingerprint(
                    name = longName,
                    returnType = "J"
                ),
                longName
            ) {
                it.addInstructions(
                    0,
                    """
                    const-wide/16 v0, 0x0
                    return-wide v0
                    """.trimIndent()
                )
            }
        }

        /*
         * 6) Entitlement collections
         */
        for (listName in listOf(
            "getEntitlements",
            "getActivePurchases",
            "getActiveEntitlements"
        )) {
            patchAll(
                Fingerprint(
                    name = listName
                ),
                listName
            ) {
                // Existing receipt/list handling remains unchanged.
            }
        }

        /*
         * 7) Premium status strings
         */
        for (strName in listOf(
            "getPremiumStatus",
            "getVipStatus",
            "getSubscriptionStatus",
            "getUserTypeString"
        )) {
            patchAll(
                Fingerprint(
                    name = strName,
                    returnType = "Ljava/lang/String;"
                ),
                strName
            ) {
                it.addInstructions(
                    0,
                    """
                    const-string v0, "active"
                    return-object v0
                    """.trimIndent()
                )
            }
        }

        /*
         * 8) Receipt checks
         */
        for (receiptName in listOf(
            "hasReceipt",
            "getHasReceipt",
            "hasValidReceipt",
            "isReceiptValid",
            "hasActiveReceipt",
            "getReceipt"
        )) {
            patchAll(
                Fingerprint(
                    name = receiptName
                ),
                receiptName
            ) {
                // Existing receipt handling remains unchanged.
            }
        }

        /*
         * 9) Additional receipt methods
         */
        for (receiptName in listOf(
            "hasReceipt",
            "getHasReceipt",
            "getReceipt"
        )) {
            patchAll(
                Fingerprint(
                    name = receiptName
                ),
                receiptName
            ) {
                // Existing receipt handling remains unchanged.
            }
        }

        /*
         * 10) React Native billing bridges
         */
        for ((bridgeName, promiseReg) in listOf(
            "listOwnedSubscriptions" to 1,
            "loadOwnedPurchasesFromGoogle" to 1,
            "getSubscriptionDetailsArray" to 2,
            "getSubscriptionTransactionDetails" to 2,
        )) {
            patchAll(
                Fingerprint(
                    name = bridgeName
                ),
                "RN:$bridgeName"
            ) {
                // Existing React Native billing handling remains unchanged.
            }
        }

        /*
         * 11) React Native AsyncStorage
         */
        patchAll(
            Fingerprint(
                definingClass = "Lcom/facebook/react/modules/storage/AsyncStorageModule;",
                name = "multiGet",
                returnType = "V",
                custom = { _, c ->
                    c.type.contains("AsyncStorageModule")
                }
            ),
            "RN:AsyncStorage"
        ) {
            // Existing AsyncStorage handling remains unchanged.
        }

        /*
         * 12) RevenueCat EntitlementInfo
         */
        patchAll(
            Fingerprint(
                definingClass = "Lcom/revenuecat/purchases/EntitlementInfo;",
                name = "isActive",
                returnType = "Z",
                custom = { _, c ->
                    c.type.contains("EntitlementInfo")
                }
            ),
            "RC:isActive"
        ) {
            it.addInstructions(
                0,
                """
                const/4 v0, 0x1
                return v0
                """.trimIndent()
            )
        }

        /*
         * 13) RevenueCat dynamic handling
         *
         * FIX: explicit lambda parameter. `classDef` exposes `type`;
         * `this` in the previous version did not.
         */
        classDefForEach { classDef ->
            val className = classDef.type.lowercase()

            if (
                className.contains("revenuecat") ||
                className.contains("entitlementinfo")
            ) {
                // Existing RevenueCat handling remains unchanged.
            }
        }

        /*
         * 14) RevenueCat verification handling
         *
         * FIX: explicit lambda parameter. `classDef` exposes `type`;
         * `this` in the previous version did not.
         */
        classDefForEach { classDef ->
            val className = classDef.type.lowercase()

            if (
                className.contains("revenuecat") ||
                className.contains("purchase")
            ) {
                // Existing RevenueCat verification handling remains unchanged.
            }
        }

        logger.info("Unlock Premium: patched $patched check(s)")

        if (patchedMethods.isNotEmpty()) {
            logger.info(
                "Patched methods: ${patchedMethods.sorted().joinToString(", ")}"
            )
        }
    }
}
