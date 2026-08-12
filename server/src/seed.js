// First-boot sample data: a Filipino menu across five stations and tables
// T1–T10, matching what the Android hub seeded. Applied as ordinary commands
// with fixed commandIds, so re-running against an existing log is a no-op via
// the engine's idempotency set.

import { updateMenu, updateTables } from "../../shared/decide.js";

const item = (id, name, priceCents, station, category) =>
  ({ id, name, priceCents, station, category, active: true });

export const SAMPLE_MENU = [
  item("m-sinigang", "Sinigang na Baboy", 32000, "MAINS", "Mains"),
  item("m-karekare", "Kare-Kare", 38000, "MAINS", "Mains"),
  item("m-adobo", "Chicken Adobo", 25000, "MAINS", "Mains"),
  item("m-bulalo", "Bulalo", 42000, "MAINS", "Mains"),
  item("m-sisig", "Sizzling Sisig", 24000, "MAINS", "Mains"),
  item("m-liempo", "Inihaw na Liempo", 22000, "GRILL", "Grill"),
  item("m-inasal", "Chicken Inasal", 19500, "GRILL", "Grill"),
  item("m-bbq", "Pork BBQ (3 sticks)", 12000, "GRILL", "Grill"),
  item("m-tilapia", "Inihaw na Tilapia", 21000, "GRILL", "Grill"),
  item("m-crispypata", "Crispy Pata", 49500, "FRYER", "Fried"),
  item("m-lumpia", "Lumpiang Shanghai", 16000, "FRYER", "Fried"),
  item("m-calamares", "Calamares", 18500, "FRYER", "Fried"),
  item("m-ricegarlic", "Garlic Rice", 5000, "MAINS", "Rice & Noodles"),
  item("m-riceplain", "Plain Rice", 3500, "MAINS", "Rice & Noodles"),
  item("m-canton", "Pancit Canton", 22000, "MAINS", "Rice & Noodles"),
  item("m-calamansi", "Calamansi Juice", 8000, "DRINKS", "Drinks"),
  item("m-gulaman", "Sago't Gulaman", 7500, "DRINKS", "Drinks"),
  item("m-soda", "Soda in Can", 6000, "DRINKS", "Drinks"),
  item("m-beer", "San Miguel Pale Pilsen", 9000, "DRINKS", "Drinks"),
  item("m-halohalo", "Halo-Halo", 15000, "DESSERT", "Dessert"),
  item("m-lecheflan", "Leche Flan", 9500, "DESSERT", "Dessert"),
  item("m-turon", "Turon", 7000, "DESSERT", "Dessert"),
];

export const SAMPLE_TABLES = Array.from({ length: 10 }, (_, i) => ({
  id: `t${i + 1}`,
  label: `T${i + 1}`,
}));

export function seedIfEmpty(engine) {
  if (engine.currentSeq() > 0) return false;
  engine.submit(updateMenu({
    commandId: "seed-menu-v1", deviceId: "hub", actor: "Hub", items: SAMPLE_MENU,
  }));
  engine.submit(updateTables({
    commandId: "seed-tables-v1", deviceId: "hub", actor: "Hub", tables: SAMPLE_TABLES,
  }));
  return true;
}
