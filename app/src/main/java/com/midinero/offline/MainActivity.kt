package com.midinero.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.midinero.offline.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(private val db: AppDatabase) : ViewModel() {

    val accounts = db.accountDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val movements = db.movementDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currencies = db.currencyDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rates = db.rateDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val goals = db.goalDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currencyOps = db.currencyOperationDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            if (accounts.value.isEmpty()) {
                db.accountDao().insert(
                    Account(
                        name = "Efectivo CUP",
                        type = "Efectivo",
                        currency = "CUP",
                        balance = 0.0
                    )
                )

                db.accountDao().insert(
                    Account(
                        name = "Capital divisas",
                        type = "Negocio",
                        currency = "CUP",
                        balance = 0.0
                    )
                )

                db.accountDao().insert(
                    Account(
                        name = "Ahorros",
                        type = "Ahorro",
                        currency = "CUP",
                        balance = 0.0
                    )
                )
            }

            if (currencies.value.isEmpty()) {
                listOf(
                    CurrencyEntity("CUP", "Peso cubano"),
                    CurrencyEntity("USD", "Dólar estadounidense"),
                    CurrencyEntity("EUR", "Euro"),
                    CurrencyEntity("CAD", "Dólar canadiense"),
                    CurrencyEntity("GBP", "Libra esterlina"),
                    CurrencyEntity("USDT", "Tether", true),
                    CurrencyEntity("BTC", "Bitcoin", true)
                ).forEach {
                    db.currencyDao().insert(it)
                }
            }
        }
    }

    fun addMovement(
        kind: String,
        amount: Double,
        currency: String,
        account: Long?,
        category: String,
        desc: String
    ) = viewModelScope.launch {

        db.movementDao().insert(
            Movement(
                kind = kind,
                amount = amount,
                currency = currency,
                accountId = account,
                category = category,
                description = desc
            )
        )

        account?.let { id ->
            accounts.value.firstOrNull { it.id == id }?.let { a ->
                val delta = when (kind) {
                    "Ingreso" -> amount
                    "Gasto" -> -amount
                    else -> 0.0
                }

                db.accountDao().update(
                    a.copy(balance = a.balance + delta)
                )
            }
        }
    }

    fun addAccount(
        name: String,
        type: String,
        currency: String
    ) = viewModelScope.launch {
        db.accountDao().insert(
            Account(
                name = name,
                type = type,
                currency = currency.uppercase()
            )
        )
    }

    fun transfer(
        fromId: Long,
        toId: Long,
        amount: Double
    ) = viewModelScope.launch {

        if (amount <= 0.0 || fromId == toId) return@launch

        val list = accounts.value

        val from = list.firstOrNull { it.id == fromId }
            ?: return@launch

        val to = list.firstOrNull { it.id == toId }
            ?: return@launch

        if (
            from.currency != to.currency ||
            from.balance < amount
        ) return@launch

        db.accountDao().update(
            from.copy(balance = from.balance - amount)
        )

        db.accountDao().update(
            to.copy(balance = to.balance + amount)
        )

        db.movementDao().insert(
            Movement(
                kind = "Transferencia",
                amount = amount,
                currency = from.currency,
                accountId = fromId,
                relatedAccountId = toId,
                description = "${from.name} → ${to.name}"
            )
        )
    }

    fun addGoal(
        name: String,
        target: Double
    ) = viewModelScope.launch {
        if (name.isNotBlank() && target > 0) {
            db.goalDao().insert(
                SavingsGoal(
                    name = name,
                    target = target
                )
            )
        }
    }

    fun contributeGoal(
        goalId: Long,
        accountId: Long,
        amount: Double
    ) = viewModelScope.launch {

        if (amount <= 0) return@launch

        val a = accounts.value.firstOrNull {
            it.id == accountId
        } ?: return@launch

        val g = goals.value.firstOrNull {
            it.id == goalId
        } ?: return@launch

        if (
            a.currency != g.currency ||
            a.balance < amount
        ) return@launch

        db.accountDao().update(
            a.copy(balance = a.balance - amount)
        )

        db.goalDao().update(
            g.copy(saved = g.saved + amount)
        )

        db.movementDao().insert(
            Movement(
                kind = "Aporte ahorro",
                amount = amount,
                currency = g.currency,
                accountId = accountId,
                category = "Ahorros",
                description = "Aporte a ${g.name}"
            )
        )
    }

    fun withdrawGoal(
        goalId: Long,
        accountId: Long,
        amount: Double
    ) = viewModelScope.launch {

        if (amount <= 0) return@launch

        val a = accounts.value.firstOrNull {
            it.id == accountId
        } ?: return@launch

        val g = goals.value.firstOrNull {
            it.id == goalId
        } ?: return@launch

        if (
            a.currency != g.currency ||
            g.saved < amount
        ) return@launch

        db.accountDao().update(
            a.copy(balance = a.balance + amount)
        )

        db.goalDao().update(
            g.copy(saved = g.saved - amount)
        )

        db.movementDao().insert(
            Movement(
                kind = "Retiro ahorro",
                amount = amount,
                currency = g.currency,
                accountId = accountId,
                category = "Ahorros",
                description = "Retiro de ${g.name}"
            )
        )
    }

    fun addCurrency(
        code: String,
        name: String,
        crypto: Boolean
    ) = viewModelScope.launch {
        db.currencyDao().insert(
            CurrencyEntity(
                code.uppercase(),
                name,
                crypto
            )
        )
    }

    fun setRate(
        pair: String,
        value: Double
    ) = viewModelScope.launch {
        db.rateDao().insert(
            Rate(
                pair.uppercase(),
                value
            )
        )
    }

    fun buy(
        currency: String,
        amount: Double,
        rate: Double
    ) = viewModelScope.launch {

        if (amount <= 0 || rate <= 0) return@launch

        val cupCost = amount * rate

        val cup =
            accounts.value.firstOrNull {
                it.name == "Capital divisas" &&
                    it.currency == "CUP"
            }
                ?: accounts.value.firstOrNull {
                    it.currency == "CUP"
                }
                ?: return@launch

        if (cup.balance < cupCost) return@launch

        var wallet =
            accounts.value.firstOrNull {
                it.currency.equals(currency, true) &&
                    it.type.contains("Divisa", true)
            }
                ?: accounts.value.firstOrNull {
                    it.currency.equals(currency, true)
                }

        if (wallet == null) {
            val newId = db.accountDao().insert(
                Account(
                    name = "Billetera ${currency.uppercase()}",
                    type = "Divisa",
                    currency = currency.uppercase(),
                    balance = 0.0
                )
            )

            wallet = Account(
                id = newId,
                name = "Billetera ${currency.uppercase()}",
                type = "Divisa",
                currency = currency.uppercase(),
                balance = 0.0
            )
        }

        db.accountDao().update(
            cup.copy(balance = cup.balance - cupCost)
        )

        db.accountDao().update(
            wallet.copy(balance = wallet.balance + amount)
        )

        db.currencyOperationDao().insert(
            CurrencyOperation(
                kind = "Compra",
                currency = currency.uppercase(),
                amount = amount,
                rate = rate,
                cupTotal = cupCost
            )
        )

        db.movementDao().insert(
            Movement(
                kind = "Compra divisas",
                amount = cupCost,
                currency = "CUP",
                accountId = cup.id,
                description = "Compra $amount $currency a $rate CUP"
            )
        )
    }

    fun sell(
        currency: String,
        amount: Double,
        rate: Double,
        buyRate: Double
    ) = viewModelScope.launch {

        if (amount <= 0 || rate <= 0 || buyRate < 0) return@launch

        val wallet = accounts.value.firstOrNull {
            it.currency.equals(currency, true)
        } ?: return@launch

        if (wallet.balance < amount) return@launch

        val cup =
            accounts.value.firstOrNull {
                it.name == "Capital divisas" &&
                    it.currency == "CUP"
            }
                ?: accounts.value.firstOrNull {
                    it.currency == "CUP"
                }
                ?: return@launch

        val cupTotal = amount * rate
        val profit = amount * (rate - buyRate)

        db.accountDao().update(
            wallet.copy(balance = wallet.balance - amount)
        )

        db.accountDao().update(
            cup.copy(balance = cup.balance + cupTotal)
        )

        db.currencyOperationDao().insert(
            CurrencyOperation(
                kind = "Venta",
                currency = currency.uppercase(),
                amount = amount,
                rate = rate,
                cupTotal = cupTotal,
                profit = profit
            )
        )

        db.movementDao().insert(
            Movement(
                kind = "Venta divisas",
                amount = cupTotal,
                currency = "CUP",
                accountId = cup.id,
                description = "Venta $amount $currency a $rate CUP; ganancia $profit CUP"
            )
        )
    }

    fun exchange(
        from: String,
        to: String,
        amount: Double
    ) = viewModelScope.launch {

        if (amount <= 0 || from.equals(to, true)) return@launch

        val received = automaticExchange(
            from.uppercase(),
            to.uppercase(),
            amount
        )

        if (received <= 0) return@launch

        val source = accounts.value.firstOrNull {
            it.currency.equals(from, true)
        } ?: return@launch

        val dest = accounts.value.firstOrNull {
            it.currency.equals(to, true)
        } ?: return@launch

        if (source.balance < amount) return@launch

        db.accountDao().update(
            source.copy(balance = source.balance - amount)
        )

        db.accountDao().update(
            dest.copy(balance = dest.balance + received)
        )

        db.movementDao().insert(
            Movement(
                kind = "Cambio entre divisas",
                amount = amount,
                currency = from.uppercase(),
                accountId = source.id,
                relatedAccountId = dest.id,
                description = "Yo doy $amount ${from.uppercase()} → Yo recibo $received ${to.uppercase()}"
            )
        )
    }

    fun calculateExchange(
        from: String,
        to: String,
        amount: Double,
        onResult: (Double) -> Unit
    ) = viewModelScope.launch {
        onResult(
            automaticExchange(
                from,
                to,
                amount
            )
        )
    }

    suspend fun automaticExchange(
        from: String,
        to: String,
        amount: Double
    ): Double {

        if (from == to) return amount

        val direct = db.rateDao().get("${from}_${to}")

        if (direct != null) {
            return amount * direct
        }

        val fromCup = db.rateDao().get("${from}_CUP")
        val toCup = db.rateDao().get("${to}_CUP")

        return if (
            fromCup != null &&
            toCup != null &&
            toCup != 0.0
        ) {
            amount * fromCup / toCup
        } else {
            0.0
        }
    }
}

@Composable
fun appVm(): MainViewModel {
    val context = androidx.compose.ui.platform.LocalContext.current

    return viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : ViewModel> create(
                modelClass: Class<T>
            ): T =
                MainViewModel(
                    AppDatabase.get(context)
                ) as T
        }
    )
}

@Composable
fun MiDineroApp(vm: MainViewModel) {

    var tab by remember {
        mutableIntStateOf(0)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {

                listOf(
                    "Inicio",
                    "Movimientos",
                    "Divisas",
                    "Cuentas",
                    "Más"
                ).forEachIndexed { i, t ->

                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                when (i) {
                                    0 -> Icons.Default.Home
                                    1 -> Icons.Default.List
                                    2 -> Icons.Default.CurrencyExchange
                                    3 -> Icons.Default.AccountBalance
                                    else -> Icons.Default.MoreHoriz
                                },
                                ""
                            )
                        },
                        label = {
                            Text(t)
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    tab = 1
                }
            ) {
                Icon(
                    Icons.Default.Add,
                    "Nuevo"
                )
            }
        }
    ) { p ->

        Box(
            Modifier
                .padding(p)
                .fillMaxSize()
        ) {

            when (tab) {
                0 -> HomeScreen(vm)
                1 -> MovementsScreen(vm)
                2 -> CurrenciesScreen(vm)
                3 -> AccountsScreen(vm)
                else -> MoreScreen(vm)
            }
        }
    }
}

@Composable
fun HomeScreen(vm: MainViewModel) {

    val accounts by vm.accounts.collectAsState()
    val movements by vm.movements.collectAsState()

    val cup = accounts
        .filter { it.currency == "CUP" }
        .sumOf { it.balance }

    val savings = accounts
        .filter {
            it.type == "Ahorro" &&
                it.currency == "CUP"
        }
        .sumOf { it.balance }

    Column(
        Modifier.padding(20.dp)
    ) {

        Text(
            "Mi Dinero",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(
            Modifier.height(16.dp)
        )

        Card(
            Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(20.dp)
            ) {

                Text("Balance disponible")

                Text(
                    "%,.2f CUP".format(cup),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(
            Modifier.height(12.dp)
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            SmallCard(
                "Ahorros",
                "%,.2f CUP".format(savings),
                Modifier.weight(1f)
            )

            SmallCard(
                "Capital divisas",
                "%,.2f CUP".format(
                    accounts.firstOrNull {
                        it.name == "Capital divisas"
                    }?.balance ?: 0.0
                ),
                Modifier.weight(1f)
            )
        }

        Spacer(
            Modifier.height(16.dp)
        )

        Text(
            "Movimientos recientes",
            fontWeight = FontWeight.Bold
        )

        LazyColumn {

            items(
                movements.take(8)
            ) { m ->

                ListItem(
                    headlineContent = {
                        Text(
                            "${m.kind}: ${m.amount} ${m.currency}"
                        )
                    },
                    supportingContent = {
                        Text(m.description)
                    }
                )

                HorizontalDivider()
            }
        }
    }
}

@Composable
fun SmallCard(
    a: String,
    b: String,
    mod: Modifier
) =
    Card(mod) {
        Column(
            Modifier.padding(14.dp)
        ) {
            Text(a)
            Text(
                b,
                fontWeight = FontWeight.Bold
            )
        }
    }

@Composable
fun MovementsScreen(vm: MainViewModel) {

    val movements by vm.movements.collectAsState()

    var show by remember {
        mutableStateOf(false)
    }

    Column(
        Modifier.padding(16.dp)
    ) {

        Text(
            "Movimientos",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 10.dp)
        ) {

            Button(
                onClick = {
                    show = true
                }
            ) {
                Text("Ingreso")
            }

            Button(
                onClick = {
                    show = true
                }
            ) {
                Text("Gasto")
            }
        }

        LazyColumn {

            items(movements) { m ->

                ListItem(
                    headlineContent = {
                        Text(
                            "${m.kind}  ${m.amount} ${m.currency}"
                        )
                    },
                    supportingContent = {
                        Text(
                            "${m.category} ${m.description}\n" +
                                SimpleDateFormat(
                                    "dd/MM/yyyy HH:mm",
                                    Locale.getDefault()
                                ).format(
                                    Date(m.dateMillis)
                                )
                        )
                    }
                )

                HorizontalDivider()
            }
        }
    }

    if (show) {
        MovementDialog(vm) {
            show = false
        }
    }
}

@Composable
fun MovementDialog(
    vm: MainViewModel,
    onClose: () -> Unit
) {

    var kind by remember {
        mutableStateOf("Gasto")
    }

    var amount by remember {
        mutableStateOf("")
    }

    var category by remember {
        mutableStateOf("General")
    }

    var desc by remember {
        mutableStateOf("")
    }

    val accounts by vm.accounts.collectAsState()

    var account by remember {
        mutableStateOf<Long?>(null)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("Nuevo movimiento")
        },
        text = {

            Column {

                Row {

                    FilterChip(
                        selected = kind == "Ingreso",
                        onClick = {
                            kind = "Ingreso"
                        },
                        label = {
                            Text("Ingreso")
                        }
                    )

                    Spacer(
                        Modifier.width(6.dp)
                    )

                    FilterChip(
                        selected = kind == "Gasto",
                        onClick = {
                            kind = "Gasto"
                        },
                        label = {
                            Text("Gasto")
                        }
                    )
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        amount = it
                    },
                    label = {
                        Text("Monto")
                    }
                )

                OutlinedTextField(
                    value = category,
                    onValueChange = {
                        category = it
                    },
                    label = {
                        Text("Categoría (Cigarros, Vencidas, Salidas…)")
                    }
                )

                OutlinedTextField(
                    value = desc,
                    onValueChange = {
                        desc = it
                    },
                    label = {
                        Text("Descripción")
                    }
                )

                Text("Cuenta")

                accounts.forEach { a ->

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        RadioButton(
                            selected = account == a.id,
                            onClick = {
                                account = a.id
                            }
                        )

                        Text(
                            "${a.name} (${a.currency})"
                        )
                    }
                }
            }
        },
        confirmButton = {

            Button(
                onClick = {

                    vm.addMovement(
                        kind,
                        amount.toDoubleOrNull() ?: 0.0,
                        "CUP",
                        account,
                        category,
                        desc
                    )

                    onClose()
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {

            TextButton(
                onClick = onClose
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun AccountsScreen(vm: MainViewModel) {

    val accounts by vm.accounts.collectAsState()

    var add by remember {
        mutableStateOf(false)
    }

    Column(
        Modifier.padding(16.dp)
    ) {

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                "Cuentas",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            IconButton(
                onClick = {
                    add = true
                }
            ) {
                Icon(
                    Icons.Default.Add,
                    "Agregar"
                )
            }
        }

        LazyColumn {

            items(accounts) { a ->

                ListItem(
                    headlineContent = {
                        Text(a.name)
                    },
                    supportingContent = {
                        Text(
                            "${a.type} • ${a.currency}"
                        )
                    },
                    trailingContent = {
                        Text(
                            "%.2f".format(a.balance)
                        )
                    }
                )

                HorizontalDivider()
            }
        }
    }

    if (add) {
        AccountDialog(vm) {
            add = false
        }
    }
}

@Composable
fun AccountDialog(
    vm: MainViewModel,
    onClose: () -> Unit
) {

    var name by remember {
        mutableStateOf("")
    }

    var type by remember {
        mutableStateOf("Efectivo")
    }

    var cur by remember {
        mutableStateOf("CUP")
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("Nueva cuenta")
        },
        text = {

            Column {

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                    },
                    label = {
                        Text("Nombre")
                    }
                )

                OutlinedTextField(
                    value = type,
                    onValueChange = {
                        type = it
                    },
                    label = {
                        Text("Tipo")
                    }
                )

                OutlinedTextField(
                    value = cur,
                    onValueChange = {
                        cur = it
                    },
                    label = {
                        Text("Moneda")
                    }
                )
            }
        },
        confirmButton = {

            Button(
                onClick = {
                    vm.addAccount(
                        name,
                        type,
                        cur
                    )
                    onClose()
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {

            TextButton(
                onClick = onClose
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun CurrenciesScreen(vm: MainViewModel) {

    val currencies by vm.currencies.collectAsState()
    val rates by vm.rates.collectAsState()

    var add by remember {
        mutableStateOf(false)
    }

    var rate by remember {
        mutableStateOf(false)
    }

    Column(
        Modifier.padding(16.dp)
    ) {

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            Text(
                "Divisas",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Row {

                IconButton(
                    onClick = {
                        rate = true
                    }
                ) {
                    Icon(
                        Icons.Default.Tune,
                        "Tasas"
                    )
                }

                IconButton(
                    onClick = {
                        add = true
                    }
                ) {
                    Icon(
                        Icons.Default.Add,
                        "Agregar"
                    )
                }
            }
        }

        LazyColumn {

            items(currencies) { c ->

                ListItem(
                    headlineContent = {
                        Text(
                            "${c.code} — ${c.name}"
                        )
                    },
                    supportingContent = {
                        Text(
                            if (c.isCrypto)
                                "Criptomoneda"
                            else
                                "Moneda"
                        )
                    }
                )
            }
        }

        Spacer(
            Modifier.height(8.dp)
        )

        Text(
            "Tasas locales",
            fontWeight = FontWeight.Bold
        )

        rates.forEach { r ->
            Text(
                "${r.pair}: ${r.value}"
            )
        }
    }

    if (add) {
        CurrencyDialog(vm) {
            add = false
        }
    }

    if (rate) {
        RateDialog(vm) {
            rate = false
        }
    }
}

@Composable
fun CurrencyDialog(
    vm: MainViewModel,
    onClose: () -> Unit
) {

    var code by remember {
        mutableStateOf("")
    }

    var name by remember {
        mutableStateOf("")
    }

    var crypto by remember {
        mutableStateOf(false)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("Agregar divisa o criptomoneda")
        },
        text = {

            Column {

                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                    },
                    label = {
                        Text("Código")
                    }
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                    },
                    label = {
                        Text("Nombre")
                    }
                )

                Row {

                    Checkbox(
                        checked = crypto,
                        onCheckedChange = {
                            crypto = !crypto
                        }
                    )

                    Text("Criptomoneda")
                }
            }
        },
        confirmButton = {

            Button(
                onClick = {
                    vm.addCurrency(
                        code,
                        name,
                        crypto
                    )
                    onClose()
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {

            TextButton(
                onClick = onClose
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun RateDialog(
    vm: MainViewModel,
    onClose: () -> Unit
) {

    var pair by remember {
        mutableStateOf("USD_CUP")
    }

    var value by remember {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("Tasa automática local")
        },
        text = {

            Column {

                Text(
                    "Ej.: USD_CUP o EUR_CUP"
                )

                OutlinedTextField(
                    value = pair,
                    onValueChange = {
                        pair = it
                    },
                    label = {
                        Text("Par")
                    }
                )

                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                    },
                    label = {
                        Text("Valor")
                    }
                )
            }
        },
        confirmButton = {

            Button(
                onClick = {

                    vm.setRate(
                        pair,
                        value.toDoubleOrNull() ?: 0.0
                    )

                    onClose()
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {

            TextButton(
                onClick = onClose
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun MoreScreen(vm: MainViewModel) {

    var section by remember {
        mutableStateOf("")
    }

    Column(
        Modifier.padding(16.dp)
    ) {

        Text(
            "Más",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        listOf(
            "Ahorros",
            "Compra / Venta",
            "Cambio entre divisas",
            "Reportes",
            "Configuración"
        ).forEach { t ->

            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                onClick = {
                    section = t
                }
            ) {

                ListItem(
                    headlineContent = {
                        Text(t)
                    },
                    trailingContent = {
                        Icon(
                            Icons.Default.ChevronRight,
                            ""
                        )
                    }
                )
            }
        }
    }

    if (section == "Ahorros") {
        SavingsDialog(vm) {
            section = ""
        }
    }

    if (section == "Compra / Venta") {
        TradeDialog(vm) {
            section = ""
        }
    }

    if (section == "Cambio entre divisas") {
        ExchangeDialog(vm) {
            section = ""
        }
    }

    if (section == "Reportes") {
        ReportDialog(vm) {
            section = ""
        }
    }

    if (section == "Configuración") {
        SettingsDialog {
            section = ""
        }
    }
}

@Composable
fun SavingsDialog(
    vm: MainViewModel,
    onClose: () -> Unit
) {

    var n by remember {
        mutableStateOf("")
    }

    var t by remember {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("Ahorros y metas")
        },
        text = {

            Column {

                OutlinedTextField(
                    value = n,
                    onValueChange = {
                        n = it
                    },
                    label = {
                        Text("Meta")
                    }
                )

                OutlinedTextField(
                    value = t,
                    onValueChange = {
                        t = it
                    },
                    label = {
                        Text("Objetivo CUP")
                    }
                )

                Text(
                    "Las metas y movimientos se guardan permanentemente."
                )
            }
        },
        confirmButton = {

            Button(
                onClick = {
                    vm.addGoal(
                        n,
                        t.toDoubleOrNull() ?: 0.0
                    )
                    onClose()
                }
            ) {
                Text("Crear")
            }
        },
        dismissButton = {

            TextButton(
                onClick = onClose
            ) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
fun TradeDialog(
    vm: MainViewModel,
    onClose: () -> Unit
) {

    var mode by remember {
        mutableStateOf("Compra")
    }

    var cur by remember {
        mutableStateOf("USD")
    }

    var amount by remember {
        mutableStateOf("")
    }

    var rate by remember {
        mutableStateOf("")
    }

    var buyRate by remember {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("Compra / Venta")
        },
        text = {

            Column {

                Row {

                    FilterChip(
                        selected = mode == "Compra",
                        onClick = {
                            mode = "Compra"
                        },
                        label = {
                            Text("Compra")
                        }
                    )

                    Spacer(
                        Modifier.width(6.dp)
                    )

                    FilterChip(
                        selected = mode == "Venta",
                        onClick = {
                            mode = "Venta"
                        },
                        label = {
                            Text("Venta")
                        }
                    )
                }

                OutlinedTextField(
                    value = cur,
                    onValueChange = {
                        cur = it
                    },
                    label = {
                        Text("Divisa")
                    }
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        amount = it
                    },
                    label = {
                        Text("Cantidad")
                    }
                )

                OutlinedTextField(
                    value = rate,
                    onValueChange = {
                        rate = it
                    },
                    label = {
                        Text("Tasa CUP")
                    }
                )

                if (mode == "Venta") {

                    OutlinedTextField(
                        value = buyRate,
                        onValueChange = {
                            buyRate = it
                        },
                        label = {
                            Text("Tasa de compra original")
                        }
                    )
                }

                Text(
                    "Total CUP: %.2f".format(
                        (amount.toDoubleOrNull() ?: 0.0) *
                            (rate.toDoubleOrNull() ?: 0.0)
                    )
                )
            }
        },
        confirmButton = {

            Button(
                onClick = {

                    if (mode == "Compra") {

                        vm.buy(
                            cur,
                            amount.toDoubleOrNull() ?: 0.0,
                            rate.toDoubleOrNull() ?: 0.0
                        )

                    } else {

                        vm.sell(
                            cur,
                            amount.toDoubleOrNull() ?: 0.0,
                            rate.toDoubleOrNull() ?: 0.0,
                            buyRate.toDoubleOrNull() ?: 0.0
                        )
                    }

                    onClose()
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {

            TextButton(
                onClick = onClose
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun ExchangeDialog(
    vm: MainViewModel,
    onClose: () -> Unit
) {

    var from by remember {
        mutableStateOf("USD")
    }

    var to by remember {
        mutableStateOf("EUR")
    }

    var amount by remember {
        mutableStateOf("")
    }

    var result by remember {
        mutableDoubleStateOf(0.0)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("Yo doy / Yo recibo")
        },
        text = {

            Column {

                OutlinedTextField(
                    value = from,
                    onValueChange = {
                        from = it
                    },
                    label = {
                        Text("Yo doy — divisa")
                    }
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        amount = it
                    },
                    label = {
                        Text("Cantidad")
                    }
                )

                OutlinedTextField(
                    value = to,
                    onValueChange = {
                        to = it
                    },
                    label = {
                        Text("Yo recibo — divisa")
                    }
                )

                Button(
                    onClick = {

                        vm.calculateExchange(
                            from.uppercase(),
                            to.uppercase(),
                            amount.toDoubleOrNull() ?: 0.0
                        ) { calculatedResult ->
                            result = calculatedResult
                        }
                    }
                ) {
                    Text("Calcular automáticamente")
                }

                Text(
                    "Recibo: %.8f %s".format(
                        result,
                        to.uppercase()
                    )
                )
            }
        },
        confirmButton = {

            TextButton(
                onClick = onClose
            ) {
                Text("Cerrar")
            }
        },
        dismissButton = {}
    )
}

@Composable
fun ReportDialog(
    vm: MainViewModel,
    onClose: () -> Unit
) {

    val m by vm.movements.collectAsState()
    val ops by vm.currencyOps.collectAsState()

    val income = m
        .filter { it.kind == "Ingreso" }
        .sumOf { it.amount }

    val expense = m
        .filter { it.kind == "Gasto" }
        .sumOf { it.amount }

    val profit = ops.sumOf {
        it.profit
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("Reportes")
        },
        text = {

            Column {

                Text(
                    "Ingresos: %.2f CUP".format(income)
                )

                Text(
                    "Gastos: %.2f CUP".format(expense)
                )

                Text(
                    "Ganancia realizada divisas: %.2f CUP".format(profit)
                )

                Text(
                    "Operaciones de divisas: ${ops.size}"
                )
            }
        },
        confirmButton = {

            TextButton(
                onClick = onClose
            ) {
                Text("Cerrar")
            }
        },
        dismissButton = {}
    )
}

@Composable
fun SettingsDialog(
    onClose: () -> Unit
) =
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text("Configuración")
        },
        text = {
            Text(
                "Mi Dinero funciona de forma local. " +
                    "Las tasas son configuradas por el usuario " +
                    "y no se consulta Internet."
            )
        },
        confirmButton = {

            TextButton(
                onClick = onClose
            ) {
                Text("Cerrar")
            }
        }
    )

class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                MiDineroApp(appVm())
            }
        }
    }
}

