package io.horizontalsystems.bankwallet.modules.market.metricspage

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.core.subscribeIO
import io.horizontalsystems.bankwallet.entities.DataState
import io.horizontalsystems.bankwallet.entities.ViewState
import io.horizontalsystems.bankwallet.modules.chart.XxxChartService
import io.horizontalsystems.bankwallet.modules.market.MarketField
import io.horizontalsystems.bankwallet.modules.market.MarketItem
import io.horizontalsystems.bankwallet.modules.market.MarketViewItem
import io.horizontalsystems.bankwallet.modules.metricchart.MetricsType
import io.horizontalsystems.bankwallet.ui.compose.Select
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MetricsPageViewModel(
    private val service: MetricsPageService,
    private val xxxChartService: XxxChartService
) : ViewModel() {

    private val disposables = CompositeDisposable()
    private val marketFields = MarketField.values().toList()
    private var marketField: MarketField
    private var marketItems: List<MarketItem> = listOf()

    val loadingLiveData = MutableLiveData<Boolean>()
    val isRefreshingLiveData = MutableLiveData<Boolean>()
    val marketLiveData = MutableLiveData<MetricsPageModule.MarketData>()
    val viewStateLiveData = MutableLiveData<ViewState>()

    val metricsType: MetricsType
        get() = service.metricsType

    init {
        marketField = when (metricsType) {
            MetricsType.Volume24h -> MarketField.Volume
            MetricsType.TotalMarketCap,
            MetricsType.DefiCap,
            MetricsType.BtcDominance,
            MetricsType.TvlInDefi -> MarketField.MarketCap
        }

        service.marketItemsObservable
            .subscribeIO { marketItemsDataState ->
                marketItemsDataState?.dataOrNull?.let {
                    marketItems = it
                    syncMarketItems(it)
                }
            }
            .let { disposables.add(it) }

        Observable.combineLatest(
            listOf(
                xxxChartService.chartItemsObservable,
                service.marketItemsObservable
            )
        ) { array -> array.map { it is DataState.Loading } }
            .map { loadingArray ->
                loadingArray.any { it }
            }
            .subscribeIO { loading ->
                loadingLiveData.postValue(loading)
            }
            .let { disposables.add(it) }

        Observable.combineLatest(
            listOf(
                xxxChartService.chartItemsObservable,
                service.marketItemsObservable
            )
        ) { it }.subscribeIO { array ->
            val viewState: ViewState? = when {
                array.any { it is DataState.Error } -> ViewState.Error(array.filterIsInstance<DataState.Error>().first().error)
                array.all { it is DataState.Success<*> } -> ViewState.Success
                else -> null
            }
            viewState?.let {
                viewStateLiveData.postValue(it)
            }
        }.let { disposables.add(it) }

        service.start()
    }

    private fun syncMarketItems(marketItems: List<MarketItem>) {
        marketLiveData.postValue(marketData(marketItems))
    }

    private fun marketData(marketItems: List<MarketItem>): MetricsPageModule.MarketData {
        val menu = MetricsPageModule.Menu(service.sortDescending, Select(marketField, marketFields))
        val marketViewItems = marketItems.map { MarketViewItem.create(it, marketField) }
        return MetricsPageModule.MarketData(menu, marketViewItems)
    }

    private fun refreshWithMinLoadingSpinnerPeriod() {
        service.refresh()
        viewModelScope.launch {
            isRefreshingLiveData.postValue(true)
            delay(1000)
            isRefreshingLiveData.postValue(false)
        }
    }

    fun onToggleSortType() {
        service.sortDescending = !service.sortDescending
    }

    fun onSelectMarketField(marketField: MarketField) {
        this.marketField = marketField
        syncMarketItems(marketItems)
    }

    fun refresh() {
        refreshWithMinLoadingSpinnerPeriod()
    }

    fun onErrorClick() {
        refreshWithMinLoadingSpinnerPeriod()
    }

    override fun onCleared() {
        service.stop()
        disposables.clear()
    }
}
