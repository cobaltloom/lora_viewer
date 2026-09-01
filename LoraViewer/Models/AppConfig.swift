import Foundation

/// Site configuration, as returned by `load_config.php`.
struct AppConfig: Decodable {
    let imeiMaster: [String: String]
    let nameMaster: [String: String]
    let imeiMasterDisplayed: [String: String]
    let nameMasterDisplayed: [String: String]
    let settings: Settings
    let eventSettings: [EventSetting]

    struct Settings: Decodable {
        let userName: String
        let siteTitle: String
        let displayCellFlag: Bool
        let updateIntervalMs: Double
        let lat: Double
        let lon: Double

        enum CodingKeys: String, CodingKey {
            case userName = "user_name"
            case siteTitle = "site_title"
            case displayCellFlag = "display_cell_flag"
            case updateIntervalMs = "update_interval"
            case lat, lon
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            userName = (try? c.decode(String.self, forKey: .userName)) ?? ""
            siteTitle = (try? c.decode(String.self, forKey: .siteTitle)) ?? ""
            let cellFlag = try c.decodeLenientString(forKey: .displayCellFlag)
            displayCellFlag = (cellFlag == "1")
            updateIntervalMs = try c.decodeLenientDouble(forKey: .updateIntervalMs) ?? 10000
            lat = try c.decodeLenientDouble(forKey: .lat) ?? 0
            lon = try c.decodeLenientDouble(forKey: .lon) ?? 0
        }
    }

    struct EventSetting: Decodable, Identifiable {
        let eventId: String
        let eventName: String
        let startDatetime: String
        let endDatetime: String
        var id: String { eventId }

        enum CodingKeys: String, CodingKey {
            case eventId = "event_id"
            case eventName = "event_name"
            case startDatetime = "start_datetime"
            case endDatetime = "end_datetime"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            eventId = try c.decodeLenientString(forKey: .eventId)
            eventName = (try? c.decode(String.self, forKey: .eventName)) ?? ""
            startDatetime = (try? c.decode(String.self, forKey: .startDatetime)) ?? ""
            endDatetime = (try? c.decode(String.self, forKey: .endDatetime)) ?? ""
        }
    }

    enum CodingKeys: String, CodingKey {
        case imeiMaster = "imei_master"
        case nameMaster = "name_master"
        case imeiMasterDisplayed = "imei_master_displayed"
        case nameMasterDisplayed = "name_master_displayed"
        case settings
        case eventSettings = "event_settings"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        imeiMaster = (try? c.decode([String: String].self, forKey: .imeiMaster)) ?? [:]
        nameMaster = (try? c.decode([String: String].self, forKey: .nameMaster)) ?? [:]
        imeiMasterDisplayed = (try? c.decode([String: String].self, forKey: .imeiMasterDisplayed)) ?? [:]
        nameMasterDisplayed = (try? c.decode([String: String].self, forKey: .nameMasterDisplayed)) ?? [:]
        settings = try c.decode(Settings.self, forKey: .settings)
        eventSettings = (try? c.decode([EventSetting].self, forKey: .eventSettings)) ?? []
    }
}

struct AppConfigResponse: Decodable {
    let result: String
    let message: String?
    let config: AppConfig
}
