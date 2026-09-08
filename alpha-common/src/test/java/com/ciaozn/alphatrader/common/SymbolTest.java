package com.ciaozn.alphatrader.common;

import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SymbolTest {

    @Test
    void parsesUnifiedForm() {
        Symbol s = Symbol.parse("BTCUSDT.PERP");
        assertThat(s.base()).isEqualTo("BTC");
        assertThat(s.quote()).isEqualTo("USDT");
        assertThat(s.unified()).isEqualTo("BTCUSDT.PERP");
    }

    @Test
    void mapsToExchangeFormats() {
        Symbol s = Symbol.parse("ETHUSDT.PERP");
        assertThat(s.binance()).isEqualTo("ETHUSDT");
        assertThat(s.okx()).isEqualTo("ETH-USDT-SWAP");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> Symbol.parse("BTC/USDT")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Symbol.parse("BTCETH.PERP")).isInstanceOf(IllegalArgumentException.class);
    }
}
