package io.horizontalsystems.bankwallet.core.ethereum

import android.util.Log
import io.horizontalsystems.bankwallet.core.ethereum.EvmTransactionFeeService.*
import io.horizontalsystems.bankwallet.core.subscribeIO
import io.horizontalsystems.bankwallet.entities.DataState
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.models.TransactionData
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import java.math.BigInteger

class EvmTransactionFeeServiceNew(
    private val evmKit: EthereumKit,
    override val gasPriceService: IEvmGasPriceService,
    private val transactionData: TransactionData,
    private val gasLimitSurchargePercent: Int = 0,
) : IEvmTransactionFeeService {

    override var transactionStatus: DataState<Transaction> = DataState.Error(GasDataError.NoTransactionData)
        private set(value) {
            field = value
            transactionStatusSubject.onNext(value)
        }
    private val transactionStatusSubject = PublishSubject.create<DataState<Transaction>>()
    override val transactionStatusObservable: Observable<DataState<Transaction>> = transactionStatusSubject

    private val disposable = CompositeDisposable()
    private var gasPriceInfoDisposable: Disposable? = null

    private val evmBalance: BigInteger
        get() = evmKit.accountState?.balance ?: BigInteger.ZERO

    init {
        Log.e("AAA", "EvmTransactionFeeServiceNew init")

        sync(gasPriceService.state)
        gasPriceService.stateObservable
            .subscribeIO({
                sync(it)
            }, {
                it.printStackTrace()
            })
            .let { disposable.add(it) }
    }

    fun onCleared() {
        Log.e("AAA", "EvmTransactionFeeServiceNew.onCleared()")
        disposable.clear()
    }

    private fun sync(gasPriceServiceState: DataState<GasPriceInfo>) {
        transactionStatus = DataState.Loading

        when (gasPriceServiceState) {
            is DataState.Error -> {
                Log.e("AAA", "sync gasPriceServiceState: Error ${gasPriceServiceState.error.javaClass.simpleName}")
                transactionStatus = gasPriceServiceState
            }
            DataState.Loading -> {
                Log.e("AAA", "sync gasPriceServiceState: Loading")
                transactionStatus = DataState.Loading
            }
            is DataState.Success -> {
                Log.e("AAA", "sync gasPriceServiceState: Success ${gasPriceServiceState.data.gasPrice.value}")
                sync(gasPriceServiceState.data)
            }
        }
    }

    private fun sync(gasPriceInfo: GasPriceInfo) {
        gasPriceInfoDisposable?.dispose()

        getTransactionAsync(gasPriceInfo, transactionData)
            .subscribeIO({
                transactionStatus = DataState.Success(it)
            }, {
                transactionStatus = DataState.Error(it)
            })
            .let {
                gasPriceInfoDisposable = it
            }
    }

    private fun getTransactionAsync(gasPriceInfo: GasPriceInfo, transactionData: TransactionData): Single<Transaction> {
        return getAdjustedTransactionDataAsync(gasPriceInfo.gasPrice, transactionData)
            .flatMap { adjustedTransactionData ->
                getGasLimitAsync(gasPriceInfo.gasPrice, adjustedTransactionData)
                    .map { estimatedGasLimit ->
                        val gasLimit = getSurchargedGasLimit(estimatedGasLimit)
                        val gasData = GasData(gasLimit, gasPriceInfo.gasPrice)
                        val errors = gasPriceInfo.errors.toMutableList()
                        if (gasData.fee > evmBalance) {
                            errors.add(FeeSettingsError.InsufficientBalance)
                        }
                        Transaction(adjustedTransactionData, gasData, gasPriceInfo.warnings, errors)
                    }
            }
    }

    private fun getAdjustedTransactionDataAsync(
        gasPrice: GasPrice,
        transactionData: TransactionData
    ): Single<TransactionData> {
        if (transactionData.input.isEmpty() && transactionData.value == evmBalance) {
            val stubTransactionData = TransactionData(transactionData.to, BigInteger.ONE, byteArrayOf())
            return getGasLimitAsync(gasPrice, stubTransactionData)
                .flatMap { estimatedGasLimit ->
                    val gasLimit = getSurchargedGasLimit(estimatedGasLimit)
                    val adjustedValue = transactionData.value - gasLimit.toBigInteger() * gasPrice.value.toBigInteger()

                    if (adjustedValue <= BigInteger.ZERO) {
                        Single.error(FeeSettingsError.InsufficientBalance)
                    } else {
                        val adjustedTransactionData = TransactionData(transactionData.to, adjustedValue, byteArrayOf())
                        Single.just(adjustedTransactionData)
                    }
                }
        } else {
            return Single.just(transactionData)
        }
    }

    private fun getGasLimitAsync(gasPrice: GasPrice, transactionData: TransactionData): Single<Long> {
        return evmKit.estimateGas(transactionData, gasPrice.value) // TODO pass GasPrice object
    }

    private fun getSurchargedGasLimit(estimatedGasLimit: Long): Long {
        return (estimatedGasLimit + estimatedGasLimit / 100.0 * gasLimitSurchargePercent).toLong()
    }
}
