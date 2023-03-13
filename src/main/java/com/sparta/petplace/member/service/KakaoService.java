package com.sparta.petplace.member.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.petplace.auth.jwt.JwtUtil;
import com.sparta.petplace.auth.jwt.RefreshToken;
import com.sparta.petplace.auth.jwt.RefreshTokenRepository;
import com.sparta.petplace.auth.jwt.TokenDto;
import com.sparta.petplace.common.ApiResponseDto;
import com.sparta.petplace.common.ResponseUtils;
import com.sparta.petplace.common.SuccessResponse;
import com.sparta.petplace.member.dto.SocialUserInfoDto;
import com.sparta.petplace.member.entity.LoginType;
import com.sparta.petplace.member.entity.Member;
import com.sparta.petplace.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoService {
    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtil jwtUtil;

    public ApiResponseDto<SuccessResponse> kakaoLogin(String code, HttpServletResponse response) throws JsonProcessingException {
        String accessToken = getToken(code);
        SocialUserInfoDto userInfoDto = getkakaoUserInfo(accessToken);
        Member member = registerKakaoUserIfNeeded(userInfoDto);
        TokenDto tokenDto = jwtUtil.createAllToken(member.getEmail());
        Optional<RefreshToken> refreshToken = refreshTokenRepository.findAllByMemberId(member.getEmail());
        if(refreshToken.isPresent()){
            refreshTokenRepository.save(refreshToken.get().updateToken(tokenDto.getRefresh_Token()));
        }else{
            RefreshToken newToken = new RefreshToken(tokenDto.getRefresh_Token(), member.getEmail());
            refreshTokenRepository.save(newToken);
        }
        jwtUtil.setHeader(response,tokenDto);
        return ResponseUtils.ok(SuccessResponse.of(HttpStatus.OK, "사용 가능한 계정"));
    }

    private String getToken(String code) throws JsonProcessingException{
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type","application/x-www.form-urlencoded;charset=utf-8");
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type","authorization_code");
        body.add("client_id","94c5891ab6cec1f5eddede64f8358dd9");
        body.add("redurect_uri","http://TeamPetPlace.shop:8080/api/member/kakao/callback");
        body.add("code",code);
        HttpEntity<MultiValueMap<String,String>> kakaoTokenRequest = new HttpEntity<>(body,headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                "https://kauth.kakao.com/oauth/token",
                HttpMethod.POST,
                kakaoTokenRequest,
                String.class
        );
        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        return jsonNode.get("access_token").asText();
    }

    private SocialUserInfoDto getkakaoUserInfo(String accessToken) throws JsonProcessingException{
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization","Bearer"+accessToken);
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");
        HttpEntity<MultiValueMap<String, String>> kakaoUserInfoRequest = new HttpEntity<>(headers);
        RestTemplate rt = new RestTemplate();
        ResponseEntity<String> response = rt.exchange(
                "https://kapi.kakao.com/v2/user/me",
                HttpMethod.POST,
                kakaoUserInfoRequest,
                String.class
        );
        String responseBody = response.getBody();
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        Long id = jsonNode.get("id").asLong();
        String nickname = jsonNode.get("properties")
                .get("nickname").asText();
        String email = jsonNode.get("kakao_account")
                .get("email").asText();
        log.info("카카오 사용자 정보: "+id+", "+nickname+", "+email);
        return new SocialUserInfoDto(id, nickname, email);
    }

    private Member registerKakaoUserIfNeeded(SocialUserInfoDto userInfoDto){
        Member findUser = memberRepository.findByEmail(userInfoDto.getEmail())
                .orElse(null);
        if(findUser == null){
            findUser = memberRepository.save(Member.builder()
                    .email(userInfoDto.getEmail())
                    .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .nickname(userInfoDto.getNickname())
                    .loginType(LoginType.KAKAO_USER)
                    .build());
        }else{
            findUser.updateLoginStatus(LoginType.KAKAO_USER);
        }
        return findUser;
    }

}