package com.velt.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velt.ui.theme.DmSans
import com.velt.ui.theme.Velt
import java.text.Normalizer

data class Country(val name: String, val iso: String, val dial: String) {
    val flag: String
        get() = iso.uppercase().map { 0x1F1E6 + (it - 'A') }
            .joinToString("") { String(Character.toChars(it)) }
}

private val allCountries: List<Country> = listOf(
    Country("México", "MX", "+52"),
    Country("Afghanistan", "AF", "+93"),
    Country("Albania", "AL", "+355"),
    Country("Algeria", "DZ", "+213"),
    Country("Andorra", "AD", "+376"),
    Country("Angola", "AO", "+244"),
    Country("Argentina", "AR", "+54"),
    Country("Armenia", "AM", "+374"),
    Country("Australia", "AU", "+61"),
    Country("Austria", "AT", "+43"),
    Country("Azerbaijan", "AZ", "+994"),
    Country("Bahamas", "BS", "+1"),
    Country("Bahrain", "BH", "+973"),
    Country("Bangladesh", "BD", "+880"),
    Country("Barbados", "BB", "+1"),
    Country("Belarus", "BY", "+375"),
    Country("Belgium", "BE", "+32"),
    Country("Belize", "BZ", "+501"),
    Country("Benin", "BJ", "+229"),
    Country("Bolivia", "BO", "+591"),
    Country("Bosnia and Herzegovina", "BA", "+387"),
    Country("Botswana", "BW", "+267"),
    Country("Brazil", "BR", "+55"),
    Country("Brunei", "BN", "+673"),
    Country("Bulgaria", "BG", "+359"),
    Country("Burkina Faso", "BF", "+226"),
    Country("Burundi", "BI", "+257"),
    Country("Cambodia", "KH", "+855"),
    Country("Cameroon", "CM", "+237"),
    Country("Canada", "CA", "+1"),
    Country("Cape Verde", "CV", "+238"),
    Country("Chad", "TD", "+235"),
    Country("Chile", "CL", "+56"),
    Country("China", "CN", "+86"),
    Country("Colombia", "CO", "+57"),
    Country("Costa Rica", "CR", "+506"),
    Country("Croatia", "HR", "+385"),
    Country("Cuba", "CU", "+53"),
    Country("Cyprus", "CY", "+357"),
    Country("Czechia", "CZ", "+420"),
    Country("Denmark", "DK", "+45"),
    Country("Dominican Republic", "DO", "+1"),
    Country("Ecuador", "EC", "+593"),
    Country("Egypt", "EG", "+20"),
    Country("El Salvador", "SV", "+503"),
    Country("Estonia", "EE", "+372"),
    Country("Ethiopia", "ET", "+251"),
    Country("Fiji", "FJ", "+679"),
    Country("Finland", "FI", "+358"),
    Country("France", "FR", "+33"),
    Country("Gabon", "GA", "+241"),
    Country("Georgia", "GE", "+995"),
    Country("Germany", "DE", "+49"),
    Country("Ghana", "GH", "+233"),
    Country("Greece", "GR", "+30"),
    Country("Guatemala", "GT", "+502"),
    Country("Guinea", "GN", "+224"),
    Country("Guyana", "GY", "+592"),
    Country("Haiti", "HT", "+509"),
    Country("Honduras", "HN", "+504"),
    Country("Hong Kong", "HK", "+852"),
    Country("Hungary", "HU", "+36"),
    Country("Iceland", "IS", "+354"),
    Country("India", "IN", "+91"),
    Country("Indonesia", "ID", "+62"),
    Country("Iran", "IR", "+98"),
    Country("Iraq", "IQ", "+964"),
    Country("Ireland", "IE", "+353"),
    Country("Israel", "IL", "+972"),
    Country("Italy", "IT", "+39"),
    Country("Ivory Coast", "CI", "+225"),
    Country("Jamaica", "JM", "+1"),
    Country("Japan", "JP", "+81"),
    Country("Jordan", "JO", "+962"),
    Country("Kazakhstan", "KZ", "+7"),
    Country("Kenya", "KE", "+254"),
    Country("Kuwait", "KW", "+965"),
    Country("Kyrgyzstan", "KG", "+996"),
    Country("Laos", "LA", "+856"),
    Country("Latvia", "LV", "+371"),
    Country("Lebanon", "LB", "+961"),
    Country("Libya", "LY", "+218"),
    Country("Liechtenstein", "LI", "+423"),
    Country("Lithuania", "LT", "+370"),
    Country("Luxembourg", "LU", "+352"),
    Country("Madagascar", "MG", "+261"),
    Country("Malawi", "MW", "+265"),
    Country("Malaysia", "MY", "+60"),
    Country("Maldives", "MV", "+960"),
    Country("Mali", "ML", "+223"),
    Country("Malta", "MT", "+356"),
    Country("Mauritius", "MU", "+230"),
    Country("Moldova", "MD", "+373"),
    Country("Monaco", "MC", "+377"),
    Country("Mongolia", "MN", "+976"),
    Country("Montenegro", "ME", "+382"),
    Country("Morocco", "MA", "+212"),
    Country("Mozambique", "MZ", "+258"),
    Country("Myanmar", "MM", "+95"),
    Country("Namibia", "NA", "+264"),
    Country("Nepal", "NP", "+977"),
    Country("Netherlands", "NL", "+31"),
    Country("New Zealand", "NZ", "+64"),
    Country("Nicaragua", "NI", "+505"),
    Country("Niger", "NE", "+227"),
    Country("Nigeria", "NG", "+234"),
    Country("North Macedonia", "MK", "+389"),
    Country("Norway", "NO", "+47"),
    Country("Oman", "OM", "+968"),
    Country("Pakistan", "PK", "+92"),
    Country("Panama", "PA", "+507"),
    Country("Papua New Guinea", "PG", "+675"),
    Country("Paraguay", "PY", "+595"),
    Country("Peru", "PE", "+51"),
    Country("Philippines", "PH", "+63"),
    Country("Poland", "PL", "+48"),
    Country("Portugal", "PT", "+351"),
    Country("Qatar", "QA", "+974"),
    Country("Romania", "RO", "+40"),
    Country("Russia", "RU", "+7"),
    Country("Rwanda", "RW", "+250"),
    Country("Saudi Arabia", "SA", "+966"),
    Country("Senegal", "SN", "+221"),
    Country("Serbia", "RS", "+381"),
    Country("Singapore", "SG", "+65"),
    Country("Slovakia", "SK", "+421"),
    Country("Slovenia", "SI", "+386"),
    Country("Somalia", "SO", "+252"),
    Country("South Africa", "ZA", "+27"),
    Country("South Korea", "KR", "+82"),
    Country("Spain", "ES", "+34"),
    Country("Sri Lanka", "LK", "+94"),
    Country("Sudan", "SD", "+249"),
    Country("Sweden", "SE", "+46"),
    Country("Switzerland", "CH", "+41"),
    Country("Syria", "SY", "+963"),
    Country("Taiwan", "TW", "+886"),
    Country("Tajikistan", "TJ", "+992"),
    Country("Tanzania", "TZ", "+255"),
    Country("Thailand", "TH", "+66"),
    Country("Togo", "TG", "+228"),
    Country("Trinidad and Tobago", "TT", "+1"),
    Country("Tunisia", "TN", "+216"),
    Country("Turkey", "TR", "+90"),
    Country("Turkmenistan", "TM", "+993"),
    Country("Uganda", "UG", "+256"),
    Country("Ukraine", "UA", "+380"),
    Country("United Arab Emirates", "AE", "+971"),
    Country("United Kingdom", "GB", "+44"),
    Country("United States", "US", "+1"),
    Country("Uruguay", "UY", "+598"),
    Country("Uzbekistan", "UZ", "+998"),
    Country("Venezuela", "VE", "+58"),
    Country("Vietnam", "VN", "+84"),
    Country("Yemen", "YE", "+967"),
    Country("Zambia", "ZM", "+260"),
    Country("Zimbabwe", "ZW", "+263")
)

private val pinnedIso = listOf("MX", "US")

val countries: List<Country> =
    pinnedIso.mapNotNull { iso -> allCountries.firstOrNull { it.iso == iso } } +
        allCountries.filter { it.iso !in pinnedIso }.sortedBy { it.name }

val defaultCountry: Country = countries.first { it.iso == "MX" }

private fun String.normalized(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerSheet(
    title: String,
    searchPlaceholder: String,
    onDismiss: () -> Unit,
    onSelect: (Country) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val q = query.normalized().trim()
        if (q.isEmpty()) countries
        else countries.filter { it.name.normalized().contains(q) || it.dial.contains(q) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Velt.Surf,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Velt.T3) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Velt.T1)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Velt.Card)
                    .border(1.dp, Velt.Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = TextStyle(fontFamily = DmSans, fontSize = 15.sp, color = Velt.T1),
                    cursorBrush = SolidColor(Velt.Cyan),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(searchPlaceholder, fontSize = 15.sp, color = Velt.T3)
                        inner()
                    }
                )
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(filtered, key = { it.iso }) { country ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(country) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(country.flag, fontSize = 22.sp)
                        Text(country.name, fontSize = 15.sp, color = Velt.T1, modifier = Modifier.weight(1f))
                        Text(country.dial, fontSize = 14.sp, color = Velt.T2)
                    }
                }
            }
        }
    }
}
