package com.yueyin.tv.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.yueyin.tv.core.cast.CastUiState
import com.yueyin.tv.core.cast.DlnaDevice
import com.yueyin.tv.ui.FnColors

/**
 * 投播设备弹层。
 *
 * 复用播放页现有的 [PhoneSheet]，只换内容：状态（扫描中/已连接/错误）+ 设备列表 + 重新搜索。
 * 列表项显示设备名与型号，而不是主机名/IP——客厅里那台电视用户认的是「客厅电视」。
 */
@Composable
internal fun PhoneCastSheet(
    visible: Boolean,
    state: CastUiState,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onPick: (DlnaDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    PhoneSheet(
        visible = visible,
        title = "投放到",
        onDismiss = onDismiss,
        heightFraction = 0.58f,
    ) {
        Text(
            "手机与设备连同一个 Wi-Fi 才能发现；投播期间手机不出声。",
            color = FnColors.Muted,
            fontSize = PhoneType.Caption,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = PhoneSpace.screenHorizontal, vertical = PhoneSpace.sm),
        )

        if (state.hasActive) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhoneSpace.screenHorizontal)
                    .background(FnColors.SurfaceAlt, RoundedCornerShape(14.dp))
                    .padding(horizontal = PhoneSpace.md, vertical = PhoneSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(FnColors.Teal, RoundedCornerShape(50)),
                )
                Spacer(Modifier.width(PhoneSpace.sm))
                Text(
                    "正在 ${state.active?.name.orEmpty()} 播放",
                    color = FnColors.Text,
                    fontSize = PhoneType.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "停止投播",
                    color = FnColors.Coral,
                    fontSize = PhoneType.Caption,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.phoneTap(onStop),
                )
            }
            Spacer(Modifier.height(PhoneSpace.md))
        }

        if (state.scanning) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhoneSpace.screenHorizontal, vertical = PhoneSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("正在搜索设备…", color = FnColors.Muted, fontSize = PhoneType.Caption)
            }
        }

        state.error?.let { message ->
            Text(
                message,
                color = FnColors.Coral,
                fontSize = PhoneType.Caption,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = PhoneSpace.screenHorizontal, vertical = PhoneSpace.xs),
            )
        }

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            state.devices.forEach { device ->
                CastDeviceRow(
                    device = device,
                    active = state.active?.usn == device.usn && state.casting,
                    onClick = { onPick(device) },
                )
            }
        }

        if (!state.scanning && state.devices.isEmpty()) {
            Text(
                "还没有发现设备。确认电视/音箱已开机并接入同一 Wi-Fi，然后点下面重新搜索。",
                color = FnColors.Muted,
                fontSize = PhoneType.Caption,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = PhoneSpace.screenHorizontal, vertical = PhoneSpace.sm),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PhoneSpace.screenHorizontal, vertical = PhoneSpace.sm),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                if (state.scanning) "搜索中…" else "重新搜索",
                color = if (state.scanning) FnColors.Muted else FnColors.Coral,
                fontSize = PhoneType.Body,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(vertical = PhoneSpace.xs)
                    .phoneTap { if (!state.scanning) onScan() },
            )
        }
    }
}

@Composable
private fun CastDeviceRow(
    device: DlnaDevice,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PhoneSpace.screenHorizontal)
            .background(
                if (active) FnColors.SelectedSoft else FnColors.SurfaceAlt,
                RoundedCornerShape(14.dp),
            )
            .phoneTap(onClick)
            .padding(horizontal = PhoneSpace.md, vertical = PhoneSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(FnColors.Surface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            PhoneIcon(
                PhoneGlyph.Cast,
                if (active) FnColors.Coral else FnColors.Muted,
                Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(PhoneSpace.md))
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                color = FnColors.Text,
                fontSize = PhoneType.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                device.subtitle,
                color = FnColors.Muted,
                fontSize = PhoneType.Micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) {
            Text("投播中", color = FnColors.Coral, fontSize = PhoneType.Micro, fontWeight = FontWeight.SemiBold)
        }
    }
}
