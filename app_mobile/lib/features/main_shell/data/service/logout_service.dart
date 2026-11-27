import 'package:dio/dio.dart';

import '../../../config/network/spring_config_header.dart';
import '../model/logout_result.dart';

/// Gọi API đăng xuất của Spring Boot: POST /mobile/admin/auth/logout.
///
/// Access token được gửi bằng Authorization Bearer; refresh token trong body được ràng buộc
/// với cùng tài khoản. Server thu hồi token trong Redis và xóa phiên đăng nhập mobile.
class LogoutService {
  LogoutService({Dio? dio})
      : _dio = dio ?? SpringConfigHeader().getApiClientNoHeaders();

  static const String _logoutPath = '/admin/auth/logout';

  final Dio _dio;

  Future<LogoutResult> logout({
    required String accessToken,
    required String refreshToken,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        _logoutPath,
        data: {'refreshToken': refreshToken},
        options: Options(headers: {'Authorization': 'Bearer $accessToken'}),
      );
      return response.statusCode == 200
          ? const LogoutSuccess()
          : const LogoutRejected();
    } on DioException {
      return const LogoutFailure();
    }
  }
}
