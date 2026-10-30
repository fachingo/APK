package ca.canadavhs.snapmobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun operatorColors(light:Boolean)=if(light) lightColorScheme(primary=Color(0xFF246B60),onPrimary=Color.White,secondary=Color(0xFF45675E),secondaryContainer=Color(0xFFD4EDE5),surfaceContainerLow=Color(0xFFEDF4F0),surfaceContainer=Color(0xFFE7F0EB),surfaceContainerHigh=Color(0xFFE0EAE4),surfaceTint=Color(0xFF246B60),primaryContainer=Color(0xFFD4EDE5),onPrimaryContainer=Color(0xFF123A33),background=Color(0xFFF5F8F7),surface=Color(0xFFFAFCFB),surfaceVariant=Color(0xFFE5EEEB),outline=Color(0xFF718880)) else darkColorScheme(primary=Color(0xFF9BD4C5),onPrimary=Color(0xFF00382E),secondary=Color(0xFFADCCC1),secondaryContainer=Color(0xFF2E4E43),surfaceContainerLow=Color(0xFF18251F),surfaceContainer=Color(0xFF1E2C25),surfaceContainerHigh=Color(0xFF26372E),surfaceTint=Color(0xFF9BD4C5),primaryContainer=Color(0xFF205047),onPrimaryContainer=Color(0xFFD4EDE5),background=Color(0xFF101817),surface=Color(0xFF15201D),surfaceVariant=Color(0xFF25352F),outline=Color(0xFF81968D))
internal val OperatorTypography=Typography(headlineLarge=androidx.compose.ui.text.TextStyle(fontSize=26.sp,lineHeight=32.sp,fontWeight=FontWeight.SemiBold),headlineSmall=androidx.compose.ui.text.TextStyle(fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold),titleLarge=androidx.compose.ui.text.TextStyle(fontSize=20.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold),titleMedium=androidx.compose.ui.text.TextStyle(fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold),bodyMedium=androidx.compose.ui.text.TextStyle(fontSize=14.sp,lineHeight=21.sp))
@Composable internal fun Disclosure(title:String,subtitle:String="",initial:Boolean=false,content:@Composable ColumnScope.()->Unit) {
 var expanded by rememberSaveable(title) {mutableStateOf(initial)}
 Column(Modifier.fillMaxWidth()) {
  TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=0.dp)) {Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start) {Text(title,style=MaterialTheme.typography.titleSmall);if(subtitle.isNotBlank()) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(expanded) "Collapse $title" else "Expand $title")}
  if(expanded) Column(verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
  HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
 }
}
internal fun operatorState(value:String)=when(value) {"UPLOADED_AS_DRAFT","LINKED_DRAFT","DRAFT"->"Draft";"ACTIVE","LINKED_ACTIVE"->"Active · publication separate";"PUBLISHED"->"Published";"NOT_UPLOADED"->"Not listed";"HIGH_CONFIDENCE"->"High confidence";"NO_RELIABLE_MATCH"->"No reliable evidence";"NEEDS_REVIEW","PARTIAL_REVIEW"->"Needs review";"WAITING_REMOTE"->"Waiting for Shopify";else->value.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()}}
@Composable internal fun StatusLabel(value:String,detail:String="") {val problem=value in setOf("ERROR","NEEDS_REVIEW","PARTIAL_REVIEW","UNCERTAIN","NO_RELIABLE_MATCH");Text(operatorState(value)+detail,style=MaterialTheme.typography.labelMedium,color=if(problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
