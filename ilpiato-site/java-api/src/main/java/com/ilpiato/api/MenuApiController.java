package com.ilpiato.api;

import com.ilpiato.api.Dto.MenuInput;
import com.ilpiato.api.Dto.MenuList;
import com.ilpiato.api.Dto.MenuView;
import com.ilpiato.api.Dto.PriceResult;
import com.ilpiato.api.Dto.SaveResult;
import com.ilpiato.api.Dto.StoreConfig;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class MenuApiController {

    private final JdbcTemplate jdbc;
    private final PricingService pricing;

    public MenuApiController(JdbcTemplate jdbc, PricingService pricing) {
        this.jdbc = jdbc;
        this.pricing = pricing;
    }

    // ── 가게 설정 ───────────────────────────────────────────────
    @GetMapping("/config")
    public StoreConfig getConfig() {
        return loadConfig();
    }

    @PutMapping("/config")
    public SaveResult putConfig(@RequestBody StoreConfig body) {
        pricing.validateConfig(body);
        List<String> notes = pricing.checkConfig(body);
        jdbc.update("""
            UPDATE store_config SET year = ?, taxpayer_type = ?, annual_sales = ?, hourly_wage = ?,
                   labor_overhead_rate = ?, profit_rate = ?, profit_mode = ?, rounding_unit = ?,
                   store_area_m2 = ?, updated_at = now()
            WHERE id = 1""",
            body.year(), body.taxpayerType(), body.annualSales(), body.hourlyWage(),
            body.laborOverheadRate(), body.profitRate(), body.profitMode(), body.roundingUnit(),
            body.storeAreaM2());
        return new SaveResult(true, notes);
    }

    // ── 메뉴 ───────────────────────────────────────────────────
    @GetMapping("/menus")
    public MenuList listMenus() {
        StoreConfig cfg = loadConfig();
        List<MenuView> items = jdbc.query("SELECT * FROM menu ORDER BY id", (rs, i) -> toView(rs, cfg));
        return new MenuList(items, storeNotes(cfg));
    }

    @PostMapping("/menus/preview")
    public PriceResult preview(@RequestBody MenuInput body) {
        pricing.validateMenu(body);
        return pricing.calculate(loadConfig(), body.ingredientCost(), body.laborMinutes(), body.manualPrice());
    }

    @PostMapping("/menus")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuView createMenu(@RequestBody MenuInput body) {
        pricing.validateMenu(body);
        Integer id = jdbc.queryForObject("""
            INSERT INTO menu (name, ingredient_cost, labor_minutes, manual_price)
            VALUES (?, ?, ?, ?) RETURNING id""",
            Integer.class, body.name(), body.ingredientCost(), body.laborMinutes(), body.manualPrice());
        return findMenu(id);
    }

    @PutMapping("/menus/{id}")
    public MenuView updateMenu(@PathVariable int id, @RequestBody MenuInput body) {
        pricing.validateMenu(body);
        int updated = jdbc.update("""
            UPDATE menu SET name = ?, ingredient_cost = ?, labor_minutes = ?, manual_price = ?,
                   updated_at = now()
            WHERE id = ?""",
            body.name(), body.ingredientCost(), body.laborMinutes(), body.manualPrice(), id);
        if (updated == 0) throw notFound();
        return findMenu(id);
    }

    @DeleteMapping("/menus/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMenu(@PathVariable int id) {
        if (jdbc.update("DELETE FROM menu WHERE id = ?", id) == 0) throw notFound();
    }

    // ── 내부 도우미 ─────────────────────────────────────────────
    private StoreConfig loadConfig() {
        return jdbc.queryForObject("SELECT * FROM store_config WHERE id = 1", (rs, i) -> new StoreConfig(
            rs.getInt("year"), rs.getString("taxpayer_type"), rs.getBigDecimal("annual_sales"),
            rs.getBigDecimal("hourly_wage"), rs.getBigDecimal("labor_overhead_rate"),
            rs.getBigDecimal("profit_rate"), rs.getString("profit_mode"),
            rs.getInt("rounding_unit"), rs.getBigDecimal("store_area_m2")));
    }

    private MenuView findMenu(int id) {
        StoreConfig cfg = loadConfig();
        return jdbc.query("SELECT * FROM menu WHERE id = ?", (rs, i) -> toView(rs, cfg), id)
            .stream().findFirst().orElseThrow(this::notFound);
    }

    private MenuView toView(ResultSet rs, StoreConfig cfg) throws SQLException {
        var ingredient = rs.getBigDecimal("ingredient_cost");
        var minutes = rs.getBigDecimal("labor_minutes");
        var manual = rs.getBigDecimal("manual_price");
        return new MenuView(rs.getInt("id"), rs.getString("name"), ingredient, minutes, manual,
            pricing.calculate(cfg, ingredient, minutes, manual));
    }

    private List<String> storeNotes(StoreConfig cfg) {
        try {
            return pricing.checkConfig(cfg);
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "메뉴를 찾을 수 없습니다.");
    }
}
