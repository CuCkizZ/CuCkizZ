package com.myvpn.wgapp.fragments

import Globals
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.core.animation.addListener
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getColor
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.billingclient.api.ProductDetails
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.firebase.messaging.FirebaseMessaging
import com.myvpn.wgapp.MainActivity
import com.myvpn.wgapp.R
import com.myvpn.wgapp.VpnErrorDisplay
import com.myvpn.wgapp.actions.MainFragmentActions
import com.myvpn.wgapp.adapters.CountryAdapter
import com.myvpn.wgapp.components.CustomDividerItemDecoration
import com.myvpn.wgapp.components.CustomRegionSelector
import com.myvpn.wgapp.databinding.FragmentMainBinding
import com.myvpn.wgapp.fragments.dialogs.NotificationBottomSheet
import com.myvpn.wgapp.fragments.dialogs.PaymentSumBottomSheetFragment
import com.myvpn.wgapp.model.Region
import com.myvpn.wgapp.model.Utils
import com.myvpn.wgapp.model.VersionCode
import com.myvpn.wgapp.model.response.BalanceResponse
import com.myvpn.wgapp.model.response.NewsItem
import com.myvpn.wgapp.model.response.PriceData
import com.myvpn.wgapp.model.response.SubscriptionInfo
import com.myvpn.wgapp.model.response.UserInfo
import com.myvpn.wgapp.repository.DataRepository
import com.myvpn.wgapp.repository.Repository
import com.myvpn.wgapp.services.ServiceManager
import com.myvpn.wgapp.states.PaymentFragmentState
import com.myvpn.wgapp.states.PaymentSumSelectionInitState
import com.myvpn.wgapp.viewModels.MainViewModel
import com.wireguard.android.backend.GoBackend
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : Fragment() {

    private lateinit var binding: FragmentMainBinding
    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var dataModel: DataRepository
    private var balanceResponseModel: BalanceResponse? = null
    private var pricesModel: List<PriceData>? = null
    private var subscriptionInfoModel: SubscriptionInfo? = null

    @Inject
    lateinit var serviceManager: ServiceManager

    @Inject
    lateinit var repository: Repository

    @Inject
    lateinit var countryAdapterFactory: CountryAdapter.Factory

    private val countryAdapter by lazy {
        countryAdapterFactory.create { region: Region ->
            setupCountry(region)
        }
    }

    private val PREPARE_REQUEST_CODE = 123
    private var isUpdateDialogShow = false
    private var wasDisconnected = false

    private val currentTime = System.currentTimeMillis()
    private var isFirstInit: Boolean? = null
    private var isFromPause: Boolean? = null

    // Add flag to prevent multiple Google Pay triggers
    private var hasHandledGooglePayState = false

    private val paymentTypeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isAdded && !isDetached && !isRemoving) {
                findNavController().navigate(R.id.payment_fragment)
            }
        }
    }

    private fun requestInAppReview(activity: Activity) {
        val sharedPreferences = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val hasReviewed = sharedPreferences.getBoolean("hasReviewed", false)

        if (hasReviewed) {
            // Пользователь уже оставил отзыв или отказался
            return
        }
        val reviewManager = ReviewManagerFactory.create(activity)
        val request = reviewManager.requestReviewFlow()

        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                // Успешно получили объект ReviewInfo
                val reviewInfo = task.result
                val flow = reviewManager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener {
                    // Процесс завершен, ничего дополнительно делать не нужно
                    sharedPreferences.edit().putBoolean("hasReviewed", true).apply()
                }
            } else {
                // Не удалось получить ReviewInfo
                task.exception?.printStackTrace()

                // Резервный вариант: перенаправляем на страницу Google Play
//                openGooglePlay(activity)
            }
        }
    }

    //
//    fun openGooglePlay(context: Context) {
//        val packageName = context.packageName
//        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//
//        try {
//            context.startActivity(intent)
//        } catch (e: ActivityNotFoundException) {
//        // Если Play Маркет недоступен, откроем ссылку в браузере
//            context.startActivity(
//                Intent(
//                    Intent.ACTION_VIEW,
//                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
//                )
//            )
//        }
//    }
    private val paymentSumReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isAdded && !isDetached && !isRemoving) {
                findNavController().navigate(R.id.payment_fragment)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initPreferences()
        VersionCode.fromContext(requireContext())
        observeLoading()
        observeBalance()
        observeError()
        setupRecyclerView(dataModel.regions)
        firstSetupAfterRun()
        setupVpnStateObserver()
        setupAndroidVersion()
        setupUI()
        fetchPrices()
        observeActions()

        setupPaymentState()
    }

    override fun onResume() {
        super.onResume()
        // Reset the Google Pay state handling flag when fragment resumes
        hasHandledGooglePayState = false
        mainViewModel.fetchDataFromServer()
        setupInitialViewState(binding.countryCardCustom)
    }

    private fun observeActions() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.actionsFLow.collect {
                    when (it) {
                        is MainFragmentActions.ShowSelectPaymentSumFragment -> {
                            PaymentSumBottomSheetFragment.newInstance(PaymentSumSelectionInitState.TRIAL_ENDED)
                                .show(parentFragmentManager, PaymentSumBottomSheetFragment.TAG)
                        }

                        is MainFragmentActions.ShowStartTrialFragment -> {
                            dataModel.paymentType = it.paymentType
                            dataModel.subscriptionType = it.subType
                            dataModel.paymentAmount = it.sum
                            val intent = Intent(PAYMENT_SUM_SELECTED)
                            intent.setPackage(context?.packageName)
                            requireContext().sendBroadcast(intent)
//                            PaymentSumBottomSheetFragment.newInstance(PaymentSumSelectionInitState.NEW_TRIAL)
//                                .show(parentFragmentManager, PaymentSumBottomSheetFragment.TAG)
                        }

                        is MainFragmentActions.ShowNotificationBottomSheetFragment -> {
                            showNotificationBottomSheet(it.newsItem)
                        }
                    }
                }
            }
        }
    }

    private fun showNotificationBottomSheet(newsItem: NewsItem) {
        val sheet = NotificationBottomSheet.newInstance(newsItem)
        sheet.onDismissListener = { newsId ->
            mainViewModel.markNewsAsRead(newsId)
        }
        sheet.show(childFragmentManager, "notification_sheet")
    }

    private fun observeError() {
        mainViewModel.error.observe(viewLifecycleOwner) { errorMessage ->
            if (errorMessage != null) {
                return@observe
            }
        }
    }

    private fun observeBalance() {
        mainViewModel.balanceResponse.observe(viewLifecycleOwner) { balanceResponse ->
            if (balanceResponse != null) {
                loadDataAndSetup(balanceResponse)
                checkAutoConnect()
                checkAppVersion(balanceResponse.androidVersion?.toInt() ?: 0, versionCode())
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val sharedPreferences =
                            requireContext().getSharedPreferences(
                                "app_settings",
                                Context.MODE_PRIVATE
                            )
                        sharedPreferences.edit().putBoolean("is_first_init", false).apply()
                    }
                    ensureActive()
                    setupInitialViewState(binding.countryCardCustom)
                }
            }
        }
    }

    private fun observeLoading() {
        mainViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            lifecycleScope.launch(Dispatchers.IO) {
                val sharedPreferences =
                    requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                isFirstInit = sharedPreferences.getBoolean("is_first_init", true)
                withContext(Dispatchers.Main) {
                    binding.progressBar.isVisible =
                        (isFirstInit == true) && (isLoading || mainViewModel.balanceResponse.value == null)
                }
            }
        }
    }

    private fun initPreferences() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sharedPreferences =
                requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            isFirstInit = sharedPreferences.getBoolean("is_first_init", true)
            withContext(Dispatchers.Main) {
                binding.progressBar.isVisible = (isFirstInit == true)
            }
        }
    }

    private fun fetchPrices() {
        lifecycleScope.launchWhenStarted {
            mainViewModel.prices.collect { prices ->
                prices?.let {
                    pricesModel = prices
                }
            }
        }
    }

    private fun setupCountry(selectedRegion: Region) {
        val selectedCountryView = binding.countryCardCustom
        val recycler = binding.regionsRecycler
        selectedCountryView.rotateArrow()
        collapse(recycler)

        dataModel.selectedRegion = selectedRegion
        val flagResId = getCountryImage(selectedRegion.ruName)
        saveSelectedRegionToPreferences(selectedRegion.ruName, flagResId)
        selectedCountryView.setCountryData(selectedRegion.name, flagResId)
        if (Globals.subscriptionEnd < currentTime) {
            collapse(recycler)
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                ensureActive()
                serviceManager.stopService()
                ensureActive()
                delay(500)
                ensureActive()
                setupConnectingView()
            }
        }
    }

    private fun setupRecyclerView(regions: List<Region>) {
        with(binding) {
            val sortedRegions = regions.sortedBy { it.ruName }
         //   Log.w("sortedRegions", sortedRegions.joinToString())
            regionsRecycler.layoutManager = LinearLayoutManager(requireContext())
            regionsRecycler.adapter = countryAdapter
            regionsRecycler.addItemDecoration(
                CustomDividerItemDecoration(
                    context = requireContext(),
                    dividerHeight = 2,
                    dividerColor = getColor(requireContext(), R.color.fragment_bg),
                    startOffsetPercentage = 0f
                )
            )
            countryAdapter.submitList(sortedRegions)

           countryCardCustom.onLinearClick = {
                Log.d("NavLinerRegion", "Tapped")
                countryCardCustom.rotateArrow()

                if (regionsRecycler.visibility == View.VISIBLE) {
                    collapse(regionsRecycler)
                } else {
                    expand(regionsRecycler)
                }
                setupInitialViewState(countryCardCustom)
            }
        }
    }

    private fun setupInitialViewState(selectedCountryView: CustomRegionSelector) {
        binding.progressBar.isVisible = false

        val (savedRegionName, savedRegionImageRes) = getSelectedRegionFromPreferences()

        val selectedRegion = if (!savedRegionName.isNullOrEmpty()) {
            dataModel.regions.find { it.ruName == savedRegionName }
        } else {
            null
        }

        if (selectedRegion != null) {
            selectedCountryView.setCountryData(selectedRegion.name, savedRegionImageRes)
            dataModel.selectedRegion = selectedRegion
        } else {
            val autoRegion = dataModel.regions.find { it.ruName == "Авто" }
            autoRegion?.let {
                val flagResId = try {
                    val resId = resources.getIdentifier(it.flag, "drawable", requireContext().packageName)
                    if (resId != 0) resId else R.drawable.auto
                } catch (e: Exception) {
                    R.drawable.auto
                }

                selectedCountryView.setCountryData(it.name, flagResId)
                dataModel.selectedRegion = autoRegion
            }
        }
    }

    private fun loadDataAndSetup(balanceResponse: BalanceResponse?) {
        binding.apply {
            if (balanceResponse != null) {
                when {
                    Utils.isTrialEnded(balanceResponse) -> {
                        OfferText.isVisible = false
                        subLinearButton.isVisible = false
                        subscriptionButton.isVisible = true
                        subscriptionText.isVisible = false
                        subscriptionButton.text = balanceResponse.subscriptionButtonText
                    }
                    balanceResponse.subscriptionType == 4 || balanceResponse.subscription == true -> {
                        subscriptionButton.isVisible = false
                        subLinearButton.isVisible = true
                        OfferText.isVisible = false
                        subscriptionText.isVisible = false
                        dateTextView.text = requireContext().getString(R.string.subscription_until_date, Utils.humanDateFormat(Globals.subscriptionEnd))
                    }

                    else -> {
                        if (balanceResponse.subscriptionText != null) {
                            subscriptionButton.isVisible = true
                            subscriptionText.isVisible = true
                            subLinearButton.isVisible = false
                            subscriptionText.text = balanceResponse.subscriptionText
                            OfferText.visibility = View.VISIBLE
                            subscriptionButton.text = balanceResponse.subscriptionButtonText
                        } else {
                            return
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch(Dispatchers.IO) {
            val sharedPreferences = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            sharedPreferences.edit().putBoolean("is_from_pause", true).apply()
        //    Log.d("is_from_pause", sharedPreferences.getBoolean("is_from_pause", false).toString())
        }
    }

    private fun firstSetupAfterRun() {
      //  dataModel.selectedRegion?.let { Log.d("firstSetupAfterRun", it.ruName) }
        binding.progressBar.isVisible = false
        val (savedRegionName, savedRegionImageRes) = getSelectedRegionFromPreferences()
        if (savedRegionName != null && savedRegionName != requireContext().getString(R.string.france)) {
            val selectedRegion = dataModel.regions.find { it.ruName == savedRegionName }
            dataModel.selectedRegion = selectedRegion
        } else {
            dataModel.selectedRegion = dataModel.regions.find { it.ruName == "Авто" }
        }
    }

    private fun setupUI() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sharedPreferences = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            isFirstInit = sharedPreferences.getBoolean("is_first_init", true)
            isFromPause = sharedPreferences.getBoolean("is_from_pause", false)
            withContext(Dispatchers.Main) {
           //     Log.d("is_from_pause", "IsFirstInit: $isFirstInit")
                updateSubscriptionUI()
                setupListeners()
            }
        }
    }

    private fun updateSubscriptionUI() {
        if (serviceManager.vpnState.value == true) {
            setupOnView()
        }
    }

    override fun onStart() {
        super.onStart()
        //mainViewModel.showPaymentSumFragment()
        showNavBar()
    }

    private fun setupListeners() {
        val navController = findNavController()
        binding.subLinearButton.setOnClickListener {
            val currentDest = navController.currentDestination?.id
            if (currentDest == R.id.main_fragment) {
                navController.navigate(R.id.action_main_fragment_to_sub_manage_fragment)
            } else {
                Log.d("NAVIGATION", "Неверный переход")
            }
        }
        binding.connectingBtn.setOnClickListener {
            serviceManager.stopService()
        }
        dissmisPopBackStack()
        payButtonTapped()
//        showNavBar()
        subscriptionText()
       // openCountriesList()
    }

    private fun payButtonTapped() {
        binding.subscriptionButton.setOnClickListener {
            if (Globals.subscriptionEnd > currentTime) {
                return@setOnClickListener
            }
            val balanceResponse = mainViewModel.balanceResponse.value
            if (balanceResponse != null && Utils.isTrialEnded(balanceResponse)) {
                dataModel.trialEnded = true
                mainViewModel.showPaymentSumFragment()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                if(mainViewModel.error.value != null) {
                    return@launch
                }
                serviceManager.stopService()
                delay(500L)
                //выбор подписки
                //paymentFragmentSheetPresent()
                //одна подписка
                dataModel.trialEnded = false
                
                // Для русских пользователей используем PaymentFragment, для английских - GooglePay
                if (Utils.isLocaleFromRussia()) {
                    mainViewModel.showStartFreeTrialFragment()
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            mainViewModel.createGooglePayState(4)
                        } catch (e: Exception) {
                            // showErrorPopup()
                        }
                    }
                }
            }
        }
    }

    private fun setupAndroidVersion() {
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(
                paymentTypeReceiver,
                IntentFilter(PAYMENT_TYPE_SELECTED),
                Context.RECEIVER_NOT_EXPORTED
            )
            context.registerReceiver(
                paymentSumReceiver,
                IntentFilter(PAYMENT_SUM_SELECTED),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(paymentTypeReceiver, IntentFilter(PAYMENT_TYPE_SELECTED))
            context.registerReceiver(paymentSumReceiver, IntentFilter(PAYMENT_SUM_SELECTED))
        }
    }

    private fun setupVpnStateObserver() {
        serviceManager.vpnState.observe(viewLifecycleOwner) {
            if (it) {
                if (Globals.subscriptionEnd > currentTime) {
                    setupOnView()
                }
            } else {
                setupOffView()
            }
        }
    }

    private fun subscriptionText() {
        val footerText = binding.OfferText.text
        val spannable = SpannableString(footerText)

        val startIndex = footerText.indexOf(requireContext().getString(R.string.is_offer))
        val endIndex = startIndex + requireContext().getString(R.string.is_offer).length

        val clickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val offerIntent =
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://myvpn.tech/docs/offer.pdf"))
                requireContext().startActivity(offerIntent)
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = ContextCompat.getColor(requireContext(), R.color.custom_blue)
                ds.isUnderlineText = true
            }
        }

        spannable.setSpan(clickableSpan, startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        with(binding.OfferText) {
            text = spannable
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = android.graphics.Color.TRANSPARENT
        }
    }

    private fun fcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                // Ваш FCM токен здесь, вы можете использовать его для отправки уведомлений
               // Log.d("FCM Token", "Token: $token")
                lifecycleScope.launch {
                    try {
                        val serverIp = repository.GetServerIp().serverIp
                        repository.setFCM(serverIp, token)
                    } catch (e: Exception) {
                    //    Log.e("FCM Token", "Ошибка при установке FCM токена", e)
                    }
                }
            } else {
             //   Log.w("FCM Token", "Получение токена не удалось", task.exception)
            }
        }
    }

    private fun Context.openUrl(url: String) {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun versionCode(): String {
        try {
            val packageManager = requireActivity().packageManager
            val packageInfo = packageManager.getPackageInfo(requireActivity().packageName, 0)

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode // Для API уровня 28 и выше
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode // Для API уровня ниже 28
            }
            return "$versionCode"
        } catch (e: PackageManager.NameNotFoundException) {
            return "0"
        }
    }

    private fun updateBalanceText() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sharedPreferences = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            isFirstInit = sharedPreferences.getBoolean("is_first_init", true)
            try {
                val serverIp = getServerIp() ?: return@launch
                val balanceRes = getBalance(serverIp) ?: return@launch
                val userInfo = repository.userInfo(serverIp)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        handleUserInfo(userInfo)
                        updateUI(balanceRes)
                    }
                }
            } catch (e: Exception) {
             //   Log.e("UpdateBalance", "Ошибка: ${e.message}", e)
            } finally {
                if (isFirstInit == true) {
                    sharedPreferences.edit().putBoolean("is_first_init", false).apply()
                }
            }
        }
    }

    private suspend fun getServerIp(): String? {
        val serverIp = repository.GetServerIp().serverIp
        return if (serverIp.isEmpty()) {
            updateBalanceText()
            null
        } else {
            serverIp
        }
    }

    private suspend fun getBalance(serverIp: String): BalanceResponse? {
        var attempt = 0
        val maxAttempts = 3
        val delayBetweenAttempts = 2000L

        while (attempt < maxAttempts) {
            val res = repository.getBalance(serverIp)
            balanceResponseModel = res

            if (res.user_id != null) {
                Globals.uid = res.user_id ?: 0
                dataModel.paymentAmount = res.monthPrice?.toDouble() ?: 299.0
                checkAppVersion(res.androidVersion?.toInt() ?: 0, versionCode())
                return res
            }
            attempt++
            if (attempt < maxAttempts) {
                delay(delayBetweenAttempts)
            }
        }
        noInternetAlertDialog()
        return null
    }

    private fun handleUserInfo(userInfo: UserInfo) {
        if (userInfo.reg_srok > 7) {
            requestInAppReview(requireActivity())
        }
    }

    private fun updateUI(res: BalanceResponse) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sharedPreferences =
                requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            isFromPause = sharedPreferences.getBoolean("is_from_pause", false)
          //  Log.d("is_from_pause", isFromPause.toString())
            withContext(Dispatchers.Main) {
                if (!isAdded || view == null) {
                    return@withContext
                }

                val monthPrice = res.monthPrice ?: 299
                binding.subscriptionButton.text = res.subscriptionButtonText
                binding.subscriptionButton.isVisible = true
                when {
                    res.subscription == true && res.subscriptionType != 4 -> {
                        binding.subscriptionButton.isVisible = false
                        binding.subLinearButton.isVisible = true
                        binding.subscriptionText.text = ""
                        binding.dateTextView.text = requireContext().getString(R.string.subscription_until_date, Utils.humanDateFormat(Globals.subscriptionEnd))
                    }

                    Globals.subscriptionEnd > currentTime -> {
                        binding.subscriptionButton.isVisible = false
                        binding.subLinearButton.isVisible = true
                        binding.subscriptionText.isVisible = true
                        binding.dateTextView.text =
                            requireContext().getString(R.string.subscription_until_date, Utils.humanDateFormat(Globals.subscriptionEnd))
                        binding.subscriptionText.text =
                            res.subscriptionText
                    }

                    else -> {
                        binding.subscriptionText.visibility = View.VISIBLE
                        binding.OfferText.visibility = View.VISIBLE
                        binding.subscriptionText.text = res.subscriptionText
                    }
                }
            }
        }
    }


    private fun saveSelectedRegionToPreferences(regionName: String, flagResId: Int) {
        val sharedPreferences = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        sharedPreferences.edit()
            .putString("selected_region_name", regionName)
            .putInt("selected_region_flag", flagResId)
            .apply()
    }

    private fun getSelectedRegionFromPreferences(): Pair<String?, Int> {
        val sharedPreferences = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val name = sharedPreferences.getString("selected_region_name", null)
        val flag = sharedPreferences.getInt("selected_region_flag", R.drawable.auto)
        return name to flag
    }


//    MARK: SetupView

    private fun setupOffView() {
        binding.inactiveBtn.isVisible = true
        binding.connectingBtn.isVisible = false
        binding.activeBtn.isVisible = false
        binding.mapImage.setImageResource(R.drawable.inactive_map)

        binding.statusText.text = requireContext().getString(R.string.not_connected)
        binding.connectStatusText.text = requireContext().getString(R.string.click_to_connect)

        binding.statusCircle.setBackgroundResource(R.drawable.bg_status_circle)
        binding.inactiveBtn.setOnClickListener {
            wasDisconnected = false
            viewLifecycleOwner.lifecycleScope.launch {

                val serverIp = repository.GetServerIp().serverIp

                val res = repository.getBalance(serverIp)
                if (res.subscription == true) {
                    setupConnectingView()
                    return@launch
                }
                if (Utils.isTrialEnded(res)) {
                    dataModel.trialEnded = true
                    mainViewModel.showPaymentSumFragment()
                    return@launch
                }
                //Одна подписка
                dataModel.trialEnded = false
                if (Utils.isLocaleFromRussia()) {
                    mainViewModel.showStartFreeTrialFragment()
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            mainViewModel.createGooglePayState(4)
                        } catch (e: Exception) {
                            // showErrorPopup()
                        }
                    }
                }
            }

        }
        lifecycleScope.launch(Dispatchers.IO) {
            val sharedPreferences = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            sharedPreferences.edit().putBoolean("auto_connect_performed", false).apply()
        }
    }

    private fun setupConnectingView() {
        if (isAdded && view != null) {
            binding.inactiveBtn.isVisible = false
            binding.connectingBtn.isVisible = true
            binding.activeBtn.isVisible = false
            binding.mapImage.setImageResource(R.drawable.inactive_map)

            binding.statusText.text = requireContext().getString(R.string.not_connected)
            binding.connectStatusText.text = requireContext().getString(R.string.connecting)

            binding.statusCircle.setBackgroundResource(R.drawable.bg_status_circle)
            binding.connectingBtn.setOnClickListener {
                serviceManager.stopService()
            }

            viewLifecycleOwner.lifecycleScope.launch {
                startVpn()
            }
        }
    }

    private fun displayVPNError() {
        activity?.let {
            if (it is VpnErrorDisplay) {
                it.displayVPNError()
            }
        }
    }

    private fun getCountryImage(regionName: String): Int = when (regionName) {
        "Авто" -> R.drawable.auto
        "Германия" -> R.drawable.de
        "США" -> R.drawable.flag_us
        "Япония" -> R.drawable.flag_jp
        "ОАЭ" -> R.drawable.flag_ae
        "Италия" -> R.drawable.flag_it
        "Испания" -> R.drawable.es
        "Россия" -> R.drawable.flag_ru
        "Франция" -> R.drawable.flag_fr
        "Китай" -> R.drawable.flag_cn
        "Турция" -> R.drawable.flag_tr
        "Нидерланды" -> R.drawable.flag_nl
        "Великобритания" -> R.drawable.flag_gb
        "Казахстан" -> R.drawable.flag_kz
        "Венгрия" -> R.drawable.flag_hu
        "Румыния" -> R.drawable.flag_ro
        "Армения" -> R.drawable.flag_am
        "Канада" -> R.drawable.flag_ca
        else -> R.drawable.auto
    }

    private fun setupOnView() {
        binding.inactiveBtn.isVisible = false
        binding.connectingBtn.isVisible = false
        binding.activeBtn.isVisible = true
        binding.mapImage.setImageResource(R.drawable.acitve_map)

        binding.statusText.text = requireContext().getString(R.string.connected)
        binding.connectStatusText.text = requireContext().getString(R.string.click_to_disconnect)

        binding.statusCircle.setBackgroundResource(R.drawable.bg_status_circle_active)

        binding.activeBtn.setOnClickListener {
            wasDisconnected = true
            serviceManager.stopService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PREPARE_REQUEST_CODE) {
            if (view == null) return
            if (resultCode == Activity.RESULT_OK) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        serviceManager.startService()
                    } catch (e: IllegalStateException) {
                        ensureActive()
                        displayVPNError()
                        ensureActive()
                        setupOffView()
                    }
                }
            } else {
                setupOffView()
            }
        }
    }

    companion object {
        const val PAYMENT_TYPE_SELECTED = "PAYMENT_TYPE_SELECTED"
        const val PAYMENT_SUM_SELECTED = "PAYMENT_SUM_SELECTED"
    }

    private fun checkAppVersion(serverVersion: Int, currentVersion: String) {
        val appVersion = currentVersion.toInt()
        if (serverVersion > appVersion) {
            if (!isUpdateDialogShow) {
                lifecycleScope.launch(Dispatchers.IO) {
                    withContext(Dispatchers.Main) {
                        showUpdateVersionDialog()
                        isUpdateDialogShow = true
                    }
                }
            }
        }
    }

    private fun checkAutoConnect() {
        val sharedPreferences = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val autoConnectEnabled = sharedPreferences.getBoolean("auto_vpn_enabled", false)
        val isFromPause = sharedPreferences.getBoolean("is_from_pause", false)
        val isFirstInit = sharedPreferences.getBoolean("is_first_init", true)
        //val alreadyPerformed = sharedPreferences.getBoolean("auto_connect_performed", false)

        Log.d("auto_connect", "Enabled: $autoConnectEnabled, FromPause: $isFromPause, " +
                "FirstInit: $isFirstInit, Subscribed: ${Globals.subscription}")

        if (autoConnectEnabled &&
            !isFromPause &&
            isFirstInit &&
            Globals.subscription
        ) {
            sharedPreferences.edit().putBoolean("auto_connect_performed", true).apply()
            Globals.autoConnectPerformed = true

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    setupConnectingView()
                    val serverIp = withContext(Dispatchers.IO) {
                        repository.GetServerIp().serverIp
                    }

                    if (serverIp.isNotEmpty()) {
                        Log.d("auto_connect", "Connecting to server: $serverIp")
                    } else {
                        Log.w("auto_connect", "Server IP is empty. Skipping connection.")
                    }
                } catch (e: Exception) {
                    Log.e("auto_connect", "Failed to auto-connect: ${e.message}", e)
                    sharedPreferences.edit().putBoolean("auto_connect_performed", false).apply()
                    Globals.autoConnectPerformed = false
                }
            }
        }
    }

    private suspend fun startVpn(): Boolean {
        try {
            val serverIp = repository.GetServerIp().serverIp
            if (serverIp.isEmpty()) {
                return true
            }

            val regionId = dataModel.selectedRegion?.region_id ?: 2
            val res = repository.getVpnConfig(serverIp, regionId)

            if (isAdded && view != null && res != null) {
                serviceManager.setConfig(res)
                try {
                    val prepareIntent = GoBackend.VpnService.prepare(requireContext())
                    if (prepareIntent != null) {
                        startActivityForResult(prepareIntent, PREPARE_REQUEST_CODE)
                    } else {
                        serviceManager.startService()
                    }
                } catch (e: IllegalStateException) {
                    displayVPNError()
                    setupOffView()
                }
            }
        } catch (e: Exception) {
          //  Log.e("SetupConnectingView", "Ошибка: ${e.message}", e)
            setupOffView()
        }
        return false
    }


    private fun noInternetAlertDialog() {
        serviceManager.stopService()
        AlertDialog.Builder(requireContext()).apply {
            setTitle(requireContext().getString(R.string.connection_error))
            setMessage(requireContext().getString(R.string.connection_error_retry))
            setPositiveButton(requireContext().getString(R.string.try_again)) { dialog, which ->
            }
            setNegativeButton(requireContext().getString(R.string.cancel)) { dialog, which ->
                dialog.dismiss()
            }
            setCancelable(false)
            show()
        }
    }

    private fun showUpdateVersionDialog() {
        AlertDialog.Builder(requireContext()).apply {
            setTitle(requireContext().getString(R.string.update_ready))
            setMessage(requireContext().getString(R.string.new_app_version_available))
            setPositiveButton(requireContext().getString(R.string.update)) { dialog, which ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data =
                        Uri.parse("https://play.google.com/store/apps/details?id=ai.myvpn.app")
                    setPackage("com.android.vending")
                }
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Snackbar.make(binding.root, requireContext().getString(R.string.no_google_play_services_found), Snackbar.LENGTH_LONG).show()
                }
            }
            setNegativeButton(requireContext().getString(R.string.cancel)) { dialog, which ->
                dialog.dismiss()
            }
            show()
        }
    }

    private fun showNavBar() {
        (activity as MainActivity).showBottomNavigation()
    }

    private fun dissmisPopBackStack() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // popback ignore
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun expand(view: View) {
        view.visibility = View.INVISIBLE
        view.measure(
            View.MeasureSpec.makeMeasureSpec((view.parent as View).width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )

        val context = view.context
        val maxHeightPx = (500 * context.resources.displayMetrics.density).toInt()
        val targetHeight = view.measuredHeight.coerceAtMost(maxHeightPx)

        view.layoutParams.height = 1
        view.visibility = View.VISIBLE

        val animator = ValueAnimator.ofInt(1, targetHeight)
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Int
            view.layoutParams.height = value
            view.requestLayout()
        }

        animator.duration = 250
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    private fun collapse(view: View) {
        val initialHeight = view.height
        val animator = ValueAnimator.ofInt(initialHeight, 0)
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Int
            view.layoutParams.height = value
            view.requestLayout()
        }

        animator.duration = 250
        animator.interpolator = DecelerateInterpolator()
        animator.addListener(onEnd = {
            view.visibility = View.GONE
            view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        })
        animator.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            context?.unregisterReceiver(paymentTypeReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered
        }
        try {
            context?.unregisterReceiver(paymentSumReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered
        }
    }

    private fun startGooglePlayPayment(productDetails: ProductDetails, offerToken: String) {
        mainViewModel.launchSubscription(requireActivity(),productDetails,offerToken)
    }

    private fun setupPaymentState() {
        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.paymentFragmentState.collect { state ->
                when(state) {
                    is PaymentFragmentState.Loading -> {}
                    is PaymentFragmentState.Error -> {}
                    is PaymentFragmentState.ShowURL -> {}
                    is PaymentFragmentState.UseGooglePay -> {
                        // Only handle Google Pay state if we haven't already handled it
                        if (!hasHandledGooglePayState) {
                            hasHandledGooglePayState = true
                            startGooglePlayPayment(state.productDetails, state.offerToken)
                            // Reset the state in ViewModel to prevent re-triggering
                            mainViewModel.resetPaymentState()
                        }
                    }
                    is PaymentFragmentState.Usdt -> {

                    }
                }
            }
        }
    }

}