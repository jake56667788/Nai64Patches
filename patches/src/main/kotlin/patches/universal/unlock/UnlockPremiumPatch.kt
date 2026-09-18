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
        val extraSet = (extraKeys ?: "").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        // Optimized isPremiumKey: single contains check, no per-key underscore branching
        val premiumSubstrings = listOf(
            "purchased", "has_receipt", "hasreceipt", "bought",
            "premium", "is_premium", "ispremium", "premium_unlocked", "premium_status", "premium_expiry", "premiumaccess", "haspremiumaccess",
            "vip", "is_vip", "vip_status", "vip_level", "vip_expiry",
            "no_ads", "noads", "ads_removed", "adsremoved", "ad_free", "adfree", "remove_ads", "removeads",
            "full_version", "fullversion", "unlocked",
            "subscribed", "is_subscribed", "subscription_active", "subscription_expires", "has_subscription", "has_active_purchase",
            "lifetime", "is_lifetime", "annual", "monthly", "trial",
            "entitlement", "entitlements", "is_entitled", "has_entitlement",
            "paid", "is_paid", "member", "pro_version", "is_pro", "pro_member",
            "subscription_expiry", "premium_expiry", "key_subs", "key_sub", "subs"
        )
        fun isPremiumKey(lower: String): Boolean {
            if (extraSet.any { it.isNotEmpty() && lower == it }) return true
            // "ignore/disregard/suspend" flags mean "disregard the premium state":
            // forcing those true inverts them (locks instead of unlocking)
            if (lower.contains("ignore") || lower.contains("disregard") || lower.contains("suspend")) return false
            for (k in premiumSubstrings) {
                if (lower.contains(k)) {
                    // guard generic "pro" inside provider/product
                    if (k == "pro_version" || k == "is_pro") return true
                    if (lower.contains("provider") || lower.contains("product") && k == "pro") continue
                    return true
                }
            }
            // standalone vip/pro
            if (lower == "pro" || lower == "vip" || lower == "pro_version" || lower == "is_pro" || lower == "is_vip") return true
            if (lower.contains("_pro_") || lower.endsWith("_pro") || lower.startsWith("pro_")) {
                if (lower.contains("provider") || lower.contains("product") || lower.contains("process") || lower.contains("progress") || lower.contains("project") || lower.contains("proceed")) return false
                return true
            }
            return false
        }

        fun patchAll(fp: Fingerprint, label: String, injector: (app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) -> Unit) {
            try {
                val matches: List<app.morphe.patcher.Match> = try { with(this@execute) { fp.matchAll() } } catch (_: Exception) { emptyList() }
                if (matches.isNotEmpty()) {
                    for (m in matches) {
                        try {
                            val method = m.method
                            if (method.implementation == null) continue
                            injector(method)
                            patched++
                            patchedMethods.add(label)
                        } catch (_: Exception) {}
                    }
                    return
                }
            } catch (_: Exception) {}
            val single = try { fp.methodOrNull } catch (_: Exception) { null }
            if (single?.implementation != null) {
                try {
                    injector(single)
                    patched++
                    patchedMethods.add(label)
                } catch (_: Exception) {}
            }
        }

        // ──────────────────────────────────────────────
        // 1) Fingerprint premium checks (cheap, indexed)
        // ──────────────────────────────────────────────

        for (checkName in listOf(
            "isPurchased", "isOwned", "isPremium", "hasPremium", "hasPremiumAccess", "isPremiumAccess",
            "isSubscribed", "hasSubscription", "isVip", "hasVip",
            "isBought", "hasBought", "wasPurchased", "hasPurchased",
            "isPro", "hasPro", "isProUser", "hasProUser", "isFullVersion", "hasFullVersion",
            "isUnlocked", "hasUnlocked", "isActive", "hasActive",
            "isLifetime", "hasLifetime", "isAnnual", "hasAnnual",
            "hasEntitlement", "isEntitled", "checkPremium", "verifyPremium",
            "isPremiumUser", "hasAdFree", "isPaidUser", "checkVip",
            "hasSubscriptionActive", "hasActivePurchase", "isProMember", "isVipUser", "hasPremiumAccessChanged",
            "hasProFeatures", "hasProAccess", "hasActiveSubscription",
        )) {
            val isGenericActive = checkName == "isActive" || checkName == "hasActive" || checkName == "isPro" || checkName == "hasPro"
            patchAll(
                Fingerprint(
                    name = checkName,
                    returnType = "Z",
                    custom = if (isGenericActive) { _, c ->
                        val t = c.type.lowercase()
                        !t.contains("okhttp") && !t.contains("ssl") && !t.contains("network") && (t.contains("premium") || t.contains("purchase") || t.contains("billing") || t.contains("subscription") || t.contains("user") || t.contains("entitle") || t.contains("vip") || t.contains("pro"))
                    } else null
                ), checkName
            ) { it.addInstructions(0, "const/4 v0, 0x1
return v0") }
        }

        for (negName in listOf("isExpired", "isCancelled", "isTrialExpired", "isLocked", "isPremiumLocked", "isContentLocked", "isHardPaywall", "isSuspended", "isPremiumSuspended")) {
            patchAll(Fingerprint(name = negName, returnType = "Z", custom = { _, c ->
                val t = c.type.lowercase()
                t.contains("premium") || t.contains("subscription") || t.contains("entitle") || t.contains("vip") || t.contains("billing") || t.contains("purchase") || t.contains("content") || t.contains("station") || t.contains("paywall")
            }), negName) {
                it.addInstructions(0, "const/4 v0, 0x0
return v0")
            }
        }

        // Paywall option flags (trial/promo/lifetime-switch availability).
        // Class-gated to billing/paywall/offer holders so generic
        // getSupportsPromo-like names elsewhere stay untouched.
        for (optName in listOf("getSupportsLifetimeSwitch", "getSupportsPromo", "getDoesOfferTrial")) {
            patchAll(Fingerprint(name = optName, returnType = "Z", custom = { _, c ->
                val t = c.type.lowercase()
                t.contains("paywall") || t.contains("billing") || t.contains("purchase") || t.contains("subscription") || t.contains("offer") || t.contains("product")
            }), optName) {
                it.addInstructions(0, "const/4 v0, 0x1
return v0")
            }
        }

        for (intName in listOf("getPremiumState", "getVipLevel", "getSubscriptionStatus", "getProState", "getVipStatus", "getUserType", "getPremiumStatusInt", "getEntitlementState")) {
            patchAll(Fingerprint(name = intName, returnType = "I"), intName) {
                it.addInstructions(0, "const/4 v0, 0x1
return v0")
            }
        }

        for (longName in listOf("getExpiryTime", "getExpireDate", "getSubscriptionExpiry", "getPremiumExpiry", "getVipExpiry", "getEntitlementExpiry")) {
            patchAll(Fingerprint(name = longName, returnType = "J", custom = { _, c ->
                val t = c.type.lowercase()
                t.contains("premium") || t.contains("subscription") || t.contains("entitle") || t.contains("vip") || t.contains("billing") || t.contains("purchase") || t.contains("pro")
            }), longName) {
                it.addInstructions(0, "const-wide v0, 0x17d2d0c0000L
return-wide v0")
            }
        }

        for (listName in listOf("getEntitlements", "getActivePurchases", "getActiveEntitlements")) {
            patchAll(Fingerprint(name = listName, custom = { m, _ -> m.returnType.contains("List") || m.returnType.contains("Collection") }), listName) {
                it.addInstructions(0, "const-string v0, "premium"
invoke-static {v0}, Ljava/util/Collections;->singletonList(Ljava/lang/Object;)Ljava/util/List;
move-result-object v0
return-object v0")
            }
        }

        for (strName in listOf("getPremiumStatus", "getVipStatus", "getSubscriptionStatus", "getUserTypeString")) {
            patchAll(Fingerprint(name = strName, returnType = "Ljava/lang/String;"), strName) {
                it.addInstructions(0, "const-string v0, "premium"
return-object v0")
            }
        }

        for (receiptName in listOf("hasReceipt", "getHasReceipt", "hasValidReceipt", "isReceiptValid", "hasActiveReceipt", "getReceipt")) {
            patchAll(Fingerprint(name = receiptName, returnType = "Z"), receiptName) {
                it.addInstructions(0, "const/4 v0, 0x1
return v0")
            }
        }

        for (receiptName in listOf("hasReceipt", "getHasReceipt", "getReceipt")) {
            patchAll(Fingerprint(name = receiptName, returnType = "Ljava/lang/String;"), "$receiptName:String") {
                it.addInstructions(0, "const-string v0, "fake_receipt_data"
return-object v0")
            }
        }

        // ──────────────────────────────────────────────
        // 1b) React Native billing bridges (Promise-based, no premium method names).
        // Ownership queries resolve with empty lists ("no purchases" semantics).
        // ──────────────────────────────────────────────

        for ((bridgeName, promiseReg) in listOf(
            "listOwnedSubscriptions" to 1,
            "loadOwnedPurchasesFromGoogle" to 1,
            "getSubscriptionDetailsArray" to 2,
            "getSubscriptionTransactionDetails" to 2,
        )) {
            patchAll(
                Fingerprint(
                    name = bridgeName,
                    returnType = "V",
                    custom = { m, _ -> m.parameterTypes.lastOrNull() == "Lcom/facebook/react/bridge/Promise;" }
                ), "RN:$bridgeName"
            ) {
                it.addInstructions(0, """
                    new-instance v0, Lcom/facebook/react/bridge/WritableNativeArray;
                    invoke-direct {v0}, Lcom/facebook/react/bridge/WritableNativeArray;-><init>()V
                    invoke-interface {p$promiseReg, v0}, Lcom/facebook/react/bridge/Promise;->resolve(Ljava/lang/Object;)V
                    return-void
                """.trimIndent())
            }
        }

        // ──────────────────────────────────────────────
        // 1c) React Native AsyncStorage (SQLite RKStorage backend).
        // Single-key reads of premium flags resolve "1", everything else
        // falls through to the original implementation untouched.
        //
        // IS_PREMIUM_SUSPENDED_KEY is explicitly excluded: it describes a
        // suspension state, not an entitlement. Leaving it untouched avoids
        // an accidental read-side premium-state override.
        // ──────────────────────────────────────────────

        patchAll(
            Fingerprint(
                definingClass = "Lcom/facebook/react/modules/storage/AsyncStorageModule;",
                name = "multiGet",
                returnType = "V",
                custom = { m, _ -> m.parameterTypes == listOf("Lcom/facebook/react/bridge/ReadableArray;", "Lcom/facebook/react/bridge/Callback;") }
            ), "RN:AsyncStorage"
        ) {
            val checks = listOf(
                "subscribed", "subscription", "premium", "entitlement", "lifetime",
                "unlocked", "remove_ads", "no_ads", "ad_free", "adfree"
            ).joinToString("
") { token ->
                """
                const-string v2, "$token"
                invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
                move-result v2
                if-nez v2, :morphe_async_hit
                """.trimIndent()
            }

            it.addInstructions(0, """
                move-object/from16 v5, p1
                invoke-interface {v5}, Lcom/facebook/react/bridge/ReadableArray;->size()I
                move-result v0
                const/4 v1, 0x1
                if-ne v0, v1, :morphe_async_orig
                const/4 v1, 0x0
                invoke-interface {v5, v1}, Lcom/facebook/react/bridge/ReadableArray;->getString(I)Ljava/lang/String;
                move-result-object v1
                if-eqz v1, :morphe_async_orig
                invoke-virtual {v1}, Ljava/lang/String;->toLowerCase()Ljava/lang/String;
                move-result-object v1

                const-string v2, "is_premium_suspended_key"
                invoke-virtual {v1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v2
                if-nez v2, :morphe_async_orig

                $checks
                goto :morphe_async_orig

                :morphe_async_hit
                invoke-static {}, Lcom/facebook/react/bridge/Arguments;->createArray()Lcom/facebook/react/bridge/WritableArray;
                move-result-object v2
                invoke-static {}, Lcom/facebook/react/bridge/Arguments;->createArray()Lcom/facebook/react/bridge/WritableArray;
                move-result-object v3
                const/4 v4, 0x0
                invoke-interface {v5, v4}, Lcom/facebook/react/bridge/ReadableArray;->getString(I)Ljava/lang/String;
                move-result-object v4
                invoke-interface {v3, v4}, Lcom/facebook/react/bridge/WritableArray;->pushString(Ljava/lang/String;)V
                const-string v4, "1"
                invoke-interface {v3, v4}, Lcom/facebook/react/bridge/WritableArray;->pushString(Ljava/lang/String;)V
                invoke-interface {v2, v3}, Lcom/facebook/react/bridge/WritableArray;->pushArray(Lcom/facebook/react/bridge/ReadableArray;)V
                const/4 v3, 0x2
                new-array v3, v3, [Ljava/lang/Object;
                const/4 v4, 0x0
                const/4 v5, 0x0
                aput-object v5, v3, v4
                const/4 v4, 0x1
                aput-object v2, v3, v4
                move-object/from16 v2, p2
                invoke-interface {v2, v3}, Lcom/facebook/react/bridge/Callback;->invoke([Ljava/lang/Object;)V
                return-void

                :morphe_async_orig
            """.trimIndent())
        }

        // ──────────────────────────────────────────────
        // 1d) RevenueCat (merged from Unlock RevenueCat Entitlements).
        // ──────────────────────────────────────────────

        patchAll(
            Fingerprint(
                definingClass = "Lcom/revenuecat/purchases/EntitlementInfo;",
                name = "isActive",
                returnType = "Z",
                custom = { m, _ -> m.parameterTypes.isEmpty() }
            ), "RC:isActive"
        ) {
            it.addInstructions(0, "const/4 v0, 0x1
return v0")
        }

        classDefForEach { classDef ->
            val tl = classDef.type.lowercase()
            if (!tl.contains("revenuecat") && !tl.contains("purchases")) return@classDefForEach
            if (tl.contains("okhttp") || tl.contains("ssl")) return@classDefForEach
            val mutableClass by lazy { try { mutableClassDefBy(classDef) } catch (_: Exception) { null } }

            for (method in classDef.methods) {
                if (method.returnType != "Z") continue
                val n = method.name.lowercase()
                if (n.contains("provider") || n.contains("product") || n.contains("progress")) continue

                val isEntitlementCheck =
                    n.contains("isactive") ||
                        n.contains("isentitled") ||
                        n.contains("hasactive") ||
                        n.contains("ispremium") ||
                        n.contains("haspremium") ||
                        n == "isactive" ||
                        n == "isentitled"

                if (!isEntitlementCheck) continue

                try {
                    if (method.implementation == null) continue
                    val mc = mutableClass ?: continue
                    mc.findMutableMethodOf(method).addInstructions(0, "const/4 v0, 0x1
return v0")
                    patched++
                    patchedMethods.add("RC:${method.name}")
                } catch (_: Exception) {}
            }
        }

        classDefForEach { classDef ->
            val tl = classDef.type.lowercase()
            if (!tl.contains("revenuecat") || !tl.contains("verification")) return@classDefForEach
            val mutableClass by lazy { try { mutableClassDefBy(classDef) } catch (_: Exception) { null } }

            for (method in classDef.methods) {
                if (method.returnType != "Z") continue
                val n = method.name.lowercase()

                if (n.contains("verify") || n.contains("enforced") || n.contains("informational")) {
                    try {
                        if (method.implementation == null) continue
                        val mc = mutableClass ?: continue
                        mc.findMutableMethodOf(method).addInstructions(0, "const/4 v0, 0x1
return v0")
                        patched++
                        patchedMethods.add("RC:verify:${method.name}")
                    } catch (_: Exception) {}
                }
            }
        }
    }
                                                                       }
