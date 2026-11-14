package ai.helper2.platform.quota;

import ai.helper2.platform.ClientKey;
import ai.helper2.platform.llm.DailyBudget;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class ClientDayQuota {

  private final ClientBudgets clients;
  private final ClientBudgets networks;

  public ClientDayQuota(ClientBudgets clients, ClientBudgets networks) {
    this.clients = clients;
    this.networks = networks;
  }

  public @Nullable Turn take(String client) {
    LocalDate day = clients.today();
    DailyBudget network = networks.budgetOf(ClientKey.network(client));
    if (!network.tryAcquire()) {
      return null;
    }
    DailyBudget own = clients.budgetOf(client);
    if (!own.tryAcquire()) {
      release(network, day);
      return null;
    }
    return new Turn(own, network, day);
  }

  public int limit() {
    return clients.limit();
  }

  public int left(String client) {
    return Math.min(clients.left(client), networks.left(ClientKey.network(client)));
  }

  private static void release(DailyBudget budget, LocalDate day) {
    budget.giveBack(day, UUID.randomUUID().toString(), Integer.MAX_VALUE);
  }

  public static final class Turn {
    private final DailyBudget own;
    private final DailyBudget network;
    private final LocalDate day;

    private Turn(DailyBudget own, DailyBudget network, LocalDate day) {
      this.own = own;
      this.network = network;
      this.day = day;
    }

    public void giveBack() {
      release(own, day);
      release(network, day);
    }
  }
}
