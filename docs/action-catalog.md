# Action catalog

Generated from the loader's code: the 48 built-in actions, by category. How to call them and what the parameter kinds accept: [Actions](actions.md). "The actor" is the country passed to `perform`.

## diplomacy

| Action | What it does | Parameters |
|---|---|---|
| `alliance` | The actor forms a military alliance with the target. | `target` country<br>`bribe` long (optional, default 0): gold offered to sweeten it |
| `alliance_invite` | The actor invites the target into its alliance bloc. | `target` country<br>`economic` bool (optional, default false): economic union instead of military alliance |
| `leave_alliance` | The actor leaves its alliance. | none |
| `kick_ally` | The actor expels the target from its alliance. | `target` country |
| `pull_out_of_alliance` | The actor pulls the target out of its current alliance. | `target` country |
| `non_aggression_pact` | The actor signs (or breaks) a non-aggression pact with the target. | `target` country<br>`breaking` bool (optional, default false): true to break an existing pact<br>`bribe` long (optional, default 0): gold offered |
| `improve_relations` | The actor spends diplomacy to improve (or worsen) relations with the target. | `target` country<br>`cost` long (optional, default 10): diplomacy points to spend<br>`worsen` bool (optional, default false) |
| `diplomatic_campaign` | The actor starts or stops a long campaign aimed at the target. | `target` country<br>`kind` text: improve, worsen, influence or integration<br>`start` bool (optional, default true): false stops it |
| `puppet` | The actor makes the target its puppet. | `target` country |
| `integrate_vassal` | The actor works to absorb its vassal. | `target` country: the vassal<br>`finalize` bool (optional, default false): true to complete the integration |
| `tribute` | The actor sets the tribute its vassal pays. | `target` country: the vassal<br>`gold_percent` int (optional, default 10)<br>`resource_percent` int (optional, default 10) |
| `demand_gold` | The actor demands gold from the target. | `target` country<br>`amount` long |
| `ultimatum` | The actor issues an ultimatum to the target. | `target` country<br>`annex` bool (optional, default false): demand annexation<br>`puppet` bool (optional, default false): demand puppet status<br>`leave_alliance` bool (optional, default false)<br>`gold` bool (optional, default false): demand gold<br>`army_threat_percent` int (optional, default 50)<br>`territory` territory (optional): land of the target to demand |

## economy

| Action | What it does | Parameters |
|---|---|---|
| `market_loan` | The actor borrows from the world market. | `principal` long<br>`days` int (optional, default 360) |
| `war_economy` | The actor sets how far its economy is geared for war. | `percent` int: 0..100 |
| `production_focus` | The actor sets the military share of production. | `military_percent` int: 0..100 |
| `build` | The actor builds upgrades (e.g. barracks, tankFactory, university). | `building` text<br>`quantity` int (optional, default 1) |

## politics

| Action | What it does | Parameters |
|---|---|---|
| `alliance_doctrine` | The actor sets its alliance doctrine. | `doctrine` text: none, defence, offensive, standards or mobilization |

## territory

| Action | What it does | Parameters |
|---|---|---|
| `cede_land` | The actor hands part of its own land to the target. | `target` country: who receives it<br>`territory` territory: land of the actor |
| `buy_land` | The actor buys land from the seller for gold. | `seller` country<br>`territory` territory: land of the seller<br>`gold` long |
| `lease_out_land` | The actor leases part of its land to the lessee for daily rent. | `lessee` country<br>`territory` territory: land of the actor<br>`daily_rent` long<br>`days` int (optional, default 3650) |
| `lease_land` | The actor leases land from the lessor for daily rent. | `lessor` country<br>`territory` territory: land of the lessor<br>`daily_rent` long<br>`days` int (optional, default 3650) |

## trade

| Action | What it does | Parameters |
|---|---|---|
| `market_access` | The actor asks the target for access to its market. | `target` country |
| `revoke_market_access` | The actor revokes the target's access to its market. | `target` country |
| `give_gold` | The actor pays gold from its treasury to the target. | `target` country<br>`amount` long |
| `give_resources` | The actor sends part of a stockpile to the receiver. | `receiver` country<br>`resource` resource<br>`amount` long |
| `alliance_gold_transfer` | Inside an alliance only: the actor moves gold from its reserves to an ally. | `target` country<br>`amount` long |
| `gift` | The actor gifts a share of one stockpile to the target, for goodwill. | `target` country<br>`resource` resource<br>`percent` int (optional, default 10) |
| `trade_contract` | The actor buys from or sells to the counterparty at a fixed price. | `counterparty` country<br>`resource` resource<br>`buying` bool: true: the actor buys<br>`amount` long<br>`unit_price` int: gold per unit<br>`monthly` bool (optional, default false): repeat every month |
| `loan` | A loan between the actor and the counterparty. | `counterparty` country<br>`actor_lends` bool: true: the actor is the lender<br>`principal` long<br>`daily_repayment` long<br>`days` int |

## war

| Action | What it does | Parameters |
|---|---|---|
| `declare_war` | The actor declares war on the target. | `target` country |
| `justify_war` | The actor starts fabricating a reason for war on the target. | `target` country<br>`reason` text (optional, default claims): war goal |
| `call_allies` | The actor calls its allies into the war against the target. | `target` country: the enemy |
| `join_ally_war` | The actor joins its ally's war against an enemy. | `ally` country<br>`enemy` country |
| `joint_war` | The actor and a partner declare war on a third country together. | `partner` country<br>`target` country |
| `request_peace` | The actor asks the target for peace (the target decides). | `target` country: the enemy |
| `lend_lease` | The actor ships equipment to the receiver in regular shipments. | `receiver` country<br>`equipment` text: infantry, artillery, tanks or aircraft<br>`per_shipment` long<br>`shipments` int (optional, default 6)<br>`cancel` bool (optional, default false) |
| `sponsor_uprising` | The actor funds rebels inside the target, starting near a city. | `target` country<br>`city` city: where it starts<br>`gold` long<br>`government` government (optional, default REBELS): rebel government |
| `regime_change` | The actor works to change the target's government. | `target` country<br>`government` government<br>`strike` bool (optional, default false): act now instead of investing<br>`points` int (optional, default 10) |

## world

| Action | What it does | Parameters |
|---|---|---|
| `make_peace` | The war between the actor and the target ends at once. | `target` country |
| `annex` | The actor absorbs the whole target country (a union, or total surrender). | `target` country |
| `transfer_land` | Land of the actor passes to the receiver immediately. | `receiver` country<br>`territory` territory: land of the actor |
| `take_land` | Land of the other country passes to the actor immediately. | `from` country<br>`territory` territory: land of 'from' |
| `set_government` | The actor's government changes form; flag and name follow. | `government` government |
| `rename` | The actor takes a new name, e.g. after a union. | `name` text |
| `change_attitude` | How the actor's government feels about the target changes. | `target` country<br>`change` int: -100..100 |
| `effect` | Applies any scenario-event effect to the actor (add_gold, add_tanks, set_tax, add_war_exhaustion ...). | `type` text: event effect type<br>`value` float (optional, default 0)<br>`text` text (optional): text argument |
| `fire_event` | Fires a scenario event (by id) for the actor. | `event` text: event id from events.json<br>`delay_days` int (optional, default 0) |
